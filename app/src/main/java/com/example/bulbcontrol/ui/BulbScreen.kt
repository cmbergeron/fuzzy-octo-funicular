package com.example.bulbcontrol.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.bulbcontrol.LightState
import com.example.bulbcontrol.PRESETS
import com.example.bulbcontrol.Preset
import com.example.bulbcontrol.ble.FoundDevice
import com.example.bulbcontrol.ble.LinkState
import com.example.bulbcontrol.ble.LinkStatus

class BulbActions(
    val onScan: () -> Unit,
    val onStopScan: () -> Unit,
    val onConnect: (String) -> Unit,
    val onDisconnect: () -> Unit,
    val onDemoMode: (Boolean) -> Unit,
    val onPower: (Boolean) -> Unit,
    val onHue: (Float) -> Unit,
    val onSaturation: (Float) -> Unit,
    val onBrightness: (Float) -> Unit,
    val onPreset: (Preset) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulbScreen(link: LinkState, light: LightState, demoMode: Boolean, actions: BulbActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bulb Control") },
                actions = {
                    Text("Demo", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = demoMode,
                        onCheckedChange = actions.onDemoMode,
                        modifier = Modifier.padding(end = 12.dp).testTag("demoSwitch"),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { ConnectionCard(link, actions) }
            if (link.status == LinkStatus.Disconnected) {
                items(link.devices, key = { it.address }) { device ->
                    DeviceRow(device) { actions.onConnect(device.address) }
                }
            }
            item { ControlsCard(light, enabled = link.status == LinkStatus.Connected, actions) }
            link.lastSent?.let { hex ->
                item {
                    Text(
                        "Last command: $hex",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(link: LinkState, actions: BulbActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (link.status) {
                LinkStatus.Disconnected -> {
                    Text("Not connected", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Turn the bulb on, then scan. It must not be connected to its own app at the same time.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (link.scanning) {
                            OutlinedButton(onClick = actions.onStopScan) { Text("Stop") }
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Scanning...")
                        } else {
                            Button(onClick = actions.onScan) { Text("Scan for bulbs") }
                        }
                    }
                    if (!link.scanning && link.devices.isEmpty() && link.error == null) {
                        Text("No bulbs found yet.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                LinkStatus.Connecting, LinkStatus.Discovering -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Connecting to ${link.deviceName}...", style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedButton(onClick = actions.onDisconnect) { Text("Cancel") }
                }
                LinkStatus.Connected -> {
                    Text("Connected to ${link.deviceName}", style = MaterialTheme.typography.titleMedium)
                    link.protocolName?.let {
                        Text("Protocol: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = actions.onDisconnect) { Text("Disconnect") }
                }
            }
            link.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DeviceRow(device: FoundDevice, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Text(device.address, style = MaterialTheme.typography.bodySmall)
            }
            Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsCard(light: LightState, enabled: Boolean, actions: BulbActions) {
    val (r, g, b) = light.rgb()
    val target = if (light.on) Color(r, g, b) else Color(0xFF3A3A3A)
    val bulbColor by animateColorAsState(target, label = "bulb")

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!enabled) {
                Text("Connect to a bulb to use these controls.", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(72.dp)
                        .shadow(if (light.on) 16.dp else 0.dp, CircleShape, spotColor = bulbColor)
                        .clip(CircleShape)
                        .background(bulbColor)
                        .testTag("bulbPreview"),
                )
                Spacer(Modifier.width(16.dp))
                Text("Power", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = light.on,
                    onCheckedChange = actions.onPower,
                    enabled = enabled,
                    modifier = Modifier.testTag("powerSwitch"),
                )
            }
            HorizontalDivider()

            Text("Color", style = MaterialTheme.typography.labelLarge)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })),
            )
            Slider(
                value = light.hue,
                onValueChange = actions.onHue,
                valueRange = 0f..360f,
                enabled = enabled && light.on,
                modifier = Modifier.testTag("hueSlider"),
            )

            Text("Saturation", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = light.saturation,
                onValueChange = actions.onSaturation,
                enabled = enabled && light.on,
            )

            Text("Brightness  ${(light.brightness * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = light.brightness,
                onValueChange = actions.onBrightness,
                valueRange = 0.05f..1f,
                enabled = enabled && light.on,
                modifier = Modifier.testTag("brightnessSlider"),
            )

            Text("Presets", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { preset ->
                    val selected = if (preset.white) light.whiteMode
                    else !light.whiteMode && light.hue == preset.hue && light.saturation == preset.saturation
                    FilterChip(
                        selected = selected,
                        onClick = { actions.onPreset(preset) },
                        label = { Text(preset.label) },
                        enabled = enabled && light.on,
                    )
                }
            }
        }
    }
}
