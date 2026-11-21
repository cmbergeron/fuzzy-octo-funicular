package com.example.bulbcontrol.ble.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.bulbcontrol.ble.BulbCommand
import no.nordicsemi.android.mesh.MeshManagerApi
import no.nordicsemi.android.mesh.MeshManagerCallbacks
import no.nordicsemi.android.mesh.MeshNetwork
import no.nordicsemi.android.mesh.MeshProvisioningStatusCallbacks
import no.nordicsemi.android.mesh.MeshStatusCallbacks
import no.nordicsemi.android.mesh.provisionerstates.ProvisioningState
import no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode
import no.nordicsemi.android.mesh.transport.ConfigAppKeyAdd
import no.nordicsemi.android.mesh.transport.ConfigAppKeyStatus
import no.nordicsemi.android.mesh.transport.ConfigCompositionDataGet
import no.nordicsemi.android.mesh.transport.ConfigCompositionDataStatus
import no.nordicsemi.android.mesh.transport.ConfigModelAppBind
import no.nordicsemi.android.mesh.transport.ConfigModelAppStatus
import no.nordicsemi.android.mesh.transport.ControlMessage
import no.nordicsemi.android.mesh.transport.GenericOnOffSetUnacknowledged
import no.nordicsemi.android.mesh.transport.LightCtlSetUnacknowledged
import no.nordicsemi.android.mesh.transport.LightHslSetUnacknowledged
import no.nordicsemi.android.mesh.transport.LightLightnessSetUnacknowledged
import no.nordicsemi.android.mesh.transport.MeshMessage
import no.nordicsemi.android.mesh.transport.ProvisionedMeshNode
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "MeshController"
private const val CONFIG_TIMEOUT_MS = 10_000L

// Bluetooth SIG model IDs (server side, i.e. what a bulb implements)
private const val GENERIC_ON_OFF_SERVER = 0x1000
private const val GENERIC_LEVEL_SERVER = 0x1002
private const val LIGHT_LIGHTNESS_SERVER = 0x1300
private const val LIGHT_CTL_SERVER = 0x1303
private const val LIGHT_CTL_TEMPERATURE_SERVER = 0x1306
private const val LIGHT_HSL_SERVER = 0x1307
private const val LIGHT_HSL_HUE_SERVER = 0x130A
private const val LIGHT_HSL_SATURATION_SERVER = 0x130B
private val MODELS_TO_BIND = setOf(
    GENERIC_ON_OFF_SERVER, GENERIC_LEVEL_SERVER, LIGHT_LIGHTNESS_SERVER, LIGHT_CTL_SERVER,
    LIGHT_CTL_TEMPERATURE_SERVER, LIGHT_HSL_SERVER, LIGHT_HSL_HUE_SERVER, LIGHT_HSL_SATURATION_SERVER,
)

/** White colour temperature used for the "White" preset on mesh bulbs. */
private const val WHITE_KELVIN = 4000

/**
 * Standard Bluetooth Mesh support (used by Sylvania/LEDVANCE and many other bulbs).
 *
 * The Nordic nRF Mesh library does the crypto; this class drives it:
 *  1. Provisioning: adds a brand-new bulb (advertising service 0x1827) to this app's
 *     own mesh network. This happens once; keys are stored on the phone.
 *  2. Configuration: over the proxy service (0x1828) gives the bulb an app key and
 *     binds it to the light models.
 *  3. Control: sends standard Generic OnOff / Light HSL / Light CTL messages.
 *
 * Every method must be called on the main thread.
 */
class MeshController(context: Context, private val host: Host) :
    MeshManagerCallbacks, MeshProvisioningStatusCallbacks, MeshStatusCallbacks {

    interface Host {
        /** Usable payload per GATT write (ATT MTU - 3). */
        val meshMtu: Int
        /** Write a (possibly segmented) PDU to the bulb's Data In characteristic. */
        fun sendMeshPdu(pdu: ByteArray)
        fun onMeshProgress(message: String)
        fun onMeshReady()
        fun onMeshFailed(message: String)
        /** Provisioning finished; host should reconnect to the same bulb via the proxy service. */
        fun onMeshProvisioned()
        fun log(message: String)
    }

    private val main = object : Handler(Looper.getMainLooper()) {
        override fun dispatchMessage(msg: android.os.Message) {
            try {
                super.dispatchMessage(msg)
            } catch (e: Throwable) {
                Log.e(TAG, "Mesh callback failed", e)
                host.log("ERROR: $e")
                e.stackTrace.take(6).forEach { host.log("    at $it") }
                host.onMeshFailed("Mesh error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
    private val prefs = context.getSharedPreferences("mesh_bulbs", Context.MODE_PRIVATE)
    private val api = MeshManagerApi(context.applicationContext)
    private var network: MeshNetwork? = null
    private var pendingAfterLoad: (() -> Unit)? = null

    private var address: String? = null
    private var node: ProvisionedMeshNode? = null
    private var tid = 0

    // Configuration steps still to send, run one at a time.
    private val configSteps = ArrayDeque<() -> MeshMessage?>()
    private var awaitingConfigReply = false

    private val configTimeout = Runnable {
        host.onMeshFailed("The bulb stopped answering during setup. Turn it off and on, then try again.")
    }

    init {
        api.setMeshManagerCallbacks(this)
        api.setProvisioningStatusCallbacks(this)
        api.setMeshStatusCallbacks(this)
        api.loadMeshNetwork()
    }

    /** True if this app already provisioned the bulb with this Bluetooth address. */
    fun isOurs(address: String): Boolean = prefs.contains(address)

    // ---------- Entry points from BleBulbLink ----------

    fun startProvisioning(address: String, deviceUuid: UUID) = whenLoaded {
        reset(address)
        host.onMeshProgress("Adding bulb to this phone (first time only)...")
        host.log("Mesh: provisioning $address, uuid $deviceUuid")
        try {
            api.identifyNode(deviceUuid)
        } catch (e: Exception) {
            host.onMeshFailed("Could not start setup: ${e.message}")
        }
    }

    fun startProxy(address: String) = whenLoaded {
        reset(address)
        val net = network ?: return@whenLoaded host.onMeshFailed("Mesh network not loaded")
        val unicast = prefs.getInt(address, -1)
        val found = net.getNode(unicast)
            ?: run {
                // Bulb's Bluetooth address may have changed; fall back to the only bulb we set up.
                val ours = prefs.all.values.filterIsInstance<Int>().toSet()
                net.nodes.singleOrNull { it.unicastAddress in ours }
            }
        if (found == null) {
            host.onMeshFailed(
                "This bulb is already set up with another app (e.g. the Sylvania app). " +
                    "Factory-reset the bulb, then connect again."
            )
            return@whenLoaded
        }
        node = found
        host.log("Mesh: proxy connected, node 0x${Integer.toHexString(found.unicastAddress)}")
        if (prefs.getBoolean(configuredKey(found), false)) {
            host.onMeshReady()
        } else {
            host.onMeshProgress("Configuring bulb (first time only)...")
            runConfiguration()
        }
    }

    fun stop() {
        main.removeCallbacks(configTimeout)
        configSteps.clear()
        awaitingConfigReply = false
        address = null
        node = null
    }

    fun handleNotification(data: ByteArray) = api.handleNotifications(host.meshMtu, data)

    fun handleWriteComplete(data: ByteArray) = api.handleWriteCallbacks(host.meshMtu, data)

    // ---------- Light control ----------

    fun send(command: BulbCommand) {
        val node = node ?: return
        val appKey = network?.appKeys?.firstOrNull() ?: return
        tid = (tid + 1) and 0xFF
        val models = node.elements.values.flatMap { it.meshModels.keys }.toSet()
        val message: MeshMessage = try {
            when (command) {
                is BulbCommand.Power -> GenericOnOffSetUnacknowledged(appKey, command.on, tid)
                is BulbCommand.Rgb -> {
                    val (h, s, v) = rgbToHsv(command.r, command.g, command.b)
                    if (LIGHT_HSL_SERVER in models) {
                        // Standard HSL: lightness 50% = full-strength colour, 100% = white.
                        LightHslSetUnacknowledged(appKey, to16(v * 0.5f), to16(h / 360f), to16(s), tid)
                    } else {
                        LightLightnessSetUnacknowledged(appKey, to16(v), tid)
                    }
                }
                is BulbCommand.White -> {
                    val level = to16(command.level / 255f)
                    when {
                        LIGHT_CTL_SERVER in models -> LightCtlSetUnacknowledged(appKey, level, WHITE_KELVIN, 0, tid)
                        LIGHT_HSL_SERVER in models -> LightHslSetUnacknowledged(appKey, level, 0, 0, tid)
                        else -> LightLightnessSetUnacknowledged(appKey, level, tid)
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            host.log("Mesh: bad value: ${e.message}")
            return
        }
        api.createMeshPdu(node.unicastAddress, message)
    }

    // ---------- Configuration ----------

    private fun runConfiguration() {
        val net = network ?: return
        val node = node ?: return
        val appKey = net.appKeys.firstOrNull() ?: net.createAppKey().also { net.addAppKey(it) }
        configSteps.clear()
        configSteps.addLast { ConfigCompositionDataGet() }
        configSteps.addLast { ConfigAppKeyAdd(net.primaryNetworkKey, appKey) }
        // Binding steps are added once the composition data (list of models) arrives.
        sendNextConfigStep()
    }

    private fun addBindSteps() {
        val node = node ?: return
        val appKeyIndex = network?.appKeys?.firstOrNull()?.keyIndex ?: return
        node.elements.values.forEach { element ->
            element.meshModels.keys.filter { it in MODELS_TO_BIND }.forEach { modelId ->
                configSteps.addLast { ConfigModelAppBind(element.elementAddress, modelId, appKeyIndex) }
            }
        }
        val models = node.elements.values.flatMap { it.meshModels.keys }
        host.log("Mesh: models ${models.joinToString { "0x" + Integer.toHexString(it) }}")
    }

    private fun sendNextConfigStep() {
        val node = node ?: return
        val step = configSteps.removeFirstOrNull()
        if (step == null) {
            main.removeCallbacks(configTimeout)
            awaitingConfigReply = false
            prefs.edit().putBoolean(configuredKey(node), true).apply()
            host.log("Mesh: configuration complete")
            host.onMeshReady()
            return
        }
        val message = step() ?: return sendNextConfigStep()
        host.log("Mesh: -> ${message.javaClass.simpleName}")
        awaitingConfigReply = true
        main.removeCallbacks(configTimeout)
        main.postDelayed(configTimeout, CONFIG_TIMEOUT_MS)
        api.createMeshPdu(node.unicastAddress, message)
    }

    // ---------- MeshManagerCallbacks ----------

    // The library loads its database on a background thread, so hop to main.
    override fun onNetworkLoaded(meshNetwork: MeshNetwork) {
        main.post {
            network = meshNetwork
            host.log("Mesh: network loaded (${meshNetwork.nodes.size} nodes)")
            val pending = pendingAfterLoad
            pendingAfterLoad = null
            pending?.invoke()
        }
    }

    override fun onNetworkUpdated(meshNetwork: MeshNetwork) {
        main.post { network = meshNetwork }
    }

    override fun onNetworkLoadFailed(error: String?) {
        main.post { host.onMeshFailed("Could not load mesh network: $error") }
    }

    override fun onNetworkImported(meshNetwork: MeshNetwork?) = Unit
    override fun onNetworkImportFailed(error: String?) = Unit

    override fun sendProvisioningPdu(meshNode: UnprovisionedMeshNode?, pdu: ByteArray) {
        main.post { host.sendMeshPdu(pdu) }
    }

    override fun onMeshPduCreated(pdu: ByteArray) {
        main.post { host.sendMeshPdu(pdu) }
    }

    override fun getMtu(): Int = host.meshMtu

    // ---------- MeshProvisioningStatusCallbacks ----------

    override fun onProvisioningStateChanged(
        meshNode: UnprovisionedMeshNode,
        state: ProvisioningState.States,
        data: ByteArray?,
    ) {
        main.post {
            host.log("Mesh: provisioning state $state")
            if (state == ProvisioningState.States.PROVISIONING_CAPABILITIES) {
                try {
                    api.startProvisioning(meshNode)
                } catch (e: Exception) {
                    host.onMeshFailed("Setup failed: ${e.message}")
                }
            }
        }
    }

    override fun onProvisioningFailed(
        meshNode: UnprovisionedMeshNode?,
        state: ProvisioningState.States?,
        data: ByteArray?,
    ) {
        main.post {
            host.log("Mesh: provisioning failed at $state")
            host.onMeshFailed("The bulb refused setup ($state). Factory-reset the bulb and try again.")
        }
    }

    override fun onProvisioningCompleted(
        meshNode: ProvisionedMeshNode,
        state: ProvisioningState.States?,
        data: ByteArray?,
    ) {
        main.post {
            val address = address ?: return@post
            prefs.edit().putInt(address, meshNode.unicastAddress).apply()
            host.log("Mesh: provisioned as 0x${Integer.toHexString(meshNode.unicastAddress)}")
            host.onMeshProgress("Bulb added. Reconnecting...")
            host.onMeshProvisioned()
        }
    }

    // ---------- MeshStatusCallbacks ----------

    override fun onMeshMessageReceived(src: Int, meshMessage: MeshMessage) {
        main.post {
            host.log("Mesh: <- ${meshMessage.javaClass.simpleName}")
            if (!awaitingConfigReply) return@post
            when (meshMessage) {
                is ConfigCompositionDataStatus -> {
                    node = network?.getNode(src) ?: node
                    addBindSteps()
                    sendNextConfigStep()
                }
                is ConfigAppKeyStatus -> {
                    // "Key already stored" is fine too, so carry on either way but log it.
                    if (!meshMessage.isSuccessful) host.log("Mesh: app key status ${meshMessage.statusCodeName}")
                    sendNextConfigStep()
                }
                is ConfigModelAppStatus -> {
                    if (!meshMessage.isSuccessful) host.log("Mesh: bind status ${meshMessage.statusCodeName}")
                    sendNextConfigStep()
                }
                else -> Unit
            }
        }
    }

    override fun onTransactionFailed(dst: Int, hasIncompleteTimerExpired: Boolean) {
        main.post { host.log("Mesh: transaction to $dst failed") }
    }

    override fun onUnknownPduReceived(src: Int, accessPayload: ByteArray?) = Unit
    override fun onBlockAcknowledgementProcessed(dst: Int, message: ControlMessage) = Unit
    override fun onBlockAcknowledgementReceived(src: Int, message: ControlMessage) = Unit
    override fun onHeartbeatMessageReceived(src: Int, message: ControlMessage) = Unit
    override fun onMeshMessageProcessed(dst: Int, meshMessage: MeshMessage) = Unit

    override fun onMessageDecryptionFailed(meshLayer: String?, errorMessage: String?) {
        main.post { host.log("Mesh: decryption failed ($meshLayer): $errorMessage") }
    }

    // ---------- Helpers ----------

    private fun reset(address: String) {
        stop()
        this.address = address
    }

    private fun whenLoaded(block: () -> Unit) {
        if (network != null) block() else pendingAfterLoad = block
    }

    private fun configuredKey(node: ProvisionedMeshNode) = "configured_${node.uuid}"

    private fun to16(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 0xFFFF).roundToInt()
}

/** RGB 0..255 -> (hue 0..360, saturation 0..1, value 0..1). */
internal fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val maxC = max(rf, max(gf, bf))
    val minC = min(rf, min(gf, bf))
    val delta = maxC - minC
    val hue = when {
        delta == 0f -> 0f
        maxC == rf -> 60f * (((gf - bf) / delta) % 6f)
        maxC == gf -> 60f * (((bf - rf) / delta) + 2f)
        else -> 60f * (((rf - gf) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val saturation = if (maxC == 0f) 0f else delta / maxC
    return Triple(hue, saturation, maxC)
}
