package com.example.bulbcontrol

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.bulbcontrol.ble.mesh.MeshController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/** Runs the real mesh library on the JVM to catch start-up crashes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeshControllerTest {

    private val sent = mutableListOf<ByteArray>()
    private val logs = mutableListOf<String>()
    private var failure: String? = null

    private val host = object : MeshController.Host {
        override val meshMtu = 20
        override fun sendMeshPdu(pdu: ByteArray) { sent += pdu }
        override fun onMeshProgress(message: String) { logs += "progress: $message" }
        override fun onMeshReady() { logs += "ready" }
        override fun onMeshFailed(message: String) { failure = message }
        override fun onMeshProvisioned() { logs += "provisioned" }
        override fun log(message: String) { logs += message }
    }

    private fun runUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test
    fun provisioningSendsInvite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = MeshController(context, host)
        controller.startProvisioning("AA:BB:CC:DD:EE:FF", UUID.randomUUID())
        runUntil { sent.isNotEmpty() || failure != null }

        println(logs.joinToString("\n"))
        assertEquals(null, failure)
        assertTrue("no PDU sent", sent.isNotEmpty())
        // Proxy PDU type 0x03 = provisioning, provisioning PDU 0x00 = invite
        assertEquals(0x03, sent[0][0].toInt())
        assertEquals(0x00, sent[0][1].toInt())

        // Simulate the GATT write finishing and the bulb replying with its capabilities
        // (1 element, FIPS P-256, no OOB of any kind).
        controller.handleWriteComplete(sent[0])
        val caps = byteArrayOf(0x03, 0x01, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val before = sent.size
        controller.handleNotification(caps)
        runUntil { sent.size > before || failure != null }
        println(logs.joinToString("\n"))
        assertEquals(null, failure)
        assertTrue("no Start PDU", sent.size > before)
        assertEquals(0x02, sent[before][1].toInt()) // Provisioning Start
    }
}
