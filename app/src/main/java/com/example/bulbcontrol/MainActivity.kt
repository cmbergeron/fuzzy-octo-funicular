package com.example.bulbcontrol

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bulbcontrol.ui.BulbActions
import com.example.bulbcontrol.ui.BulbScreen

class MainActivity : ComponentActivity() {

    private val viewModel: BulbViewModel by viewModels()

    private val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                ensureBluetoothOnThenScan()
            } else {
                Toast.makeText(this, "Bluetooth permission is needed to find your bulb", Toast.LENGTH_LONG).show()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isBluetoothOn()) viewModel.startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val link by viewModel.linkState.collectAsStateWithLifecycle()
            val light by viewModel.light.collectAsStateWithLifecycle()
            val demo by viewModel.demoMode.collectAsStateWithLifecycle()

            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                BulbScreen(
                    link = link,
                    light = light,
                    demoMode = demo,
                    actions = BulbActions(
                        onScan = ::onScanClicked,
                        onStopScan = viewModel::stopScan,
                        onConnect = viewModel::connect,
                        onDisconnect = viewModel::disconnect,
                        onDemoMode = viewModel::setDemoMode,
                        onPower = viewModel::setPower,
                        onHue = viewModel::setHue,
                        onSaturation = viewModel::setSaturation,
                        onBrightness = viewModel::setBrightness,
                        onPreset = viewModel::applyPreset,
                    ),
                )
            }
        }
    }

    private fun onScanClicked() {
        if (viewModel.demoMode.value) {
            viewModel.startScan()
            return
        }
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) ensureBluetoothOnThenScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun ensureBluetoothOnThenScan() {
        if (isBluetoothOn()) {
            viewModel.startScan()
        } else {
            try {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) {
                // No permission, or no Bluetooth at all (e.g. some emulators):
                // startScan() shows a readable error on screen instead.
                viewModel.startScan()
            }
        }
    }

    private fun isBluetoothOn(): Boolean =
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
}
