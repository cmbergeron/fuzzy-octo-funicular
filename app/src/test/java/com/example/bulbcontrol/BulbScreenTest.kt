package com.example.bulbcontrol

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import com.example.bulbcontrol.ble.FoundDevice
import com.example.bulbcontrol.ble.LinkState
import com.example.bulbcontrol.ble.LinkStatus
import com.example.bulbcontrol.ui.BulbActions
import com.example.bulbcontrol.ui.BulbScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders the screen in each connection state to catch UI crashes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BulbScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = BulbActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test
    fun connectingWithLogRenders() {
        val state = LinkState(
            status = LinkStatus.Connecting,
            deviceName = "Sylvania",
            detail = "Adding bulb to this phone (first time only)...",
            log = listOf("12:00:00 Connecting", "12:00:01 MTU 247"),
            devices = listOf(FoundDevice("AA", "Sylvania", -50)),
        )
        rule.setContent { BulbScreen(state, LightState(), demoMode = false, actions = actions) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Show"))
        rule.onNodeWithText("Show").performClick()
        rule.onNodeWithText("12:00:00 Connecting\n12:00:01 MTU 247").assertExists()
    }

    @Test
    fun disconnectedWithDevicesRenders() {
        val state = LinkState(devices = listOf(FoundDevice("AA", "Sylvania", -50), FoundDevice("BB", "Other", -70)))
        rule.setContent { BulbScreen(state, LightState(), demoMode = false, actions = actions) }
        rule.onNodeWithText("Sylvania").assertExists()
    }
}
