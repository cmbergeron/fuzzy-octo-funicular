package com.example.bulbcontrol

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real app on an emulator/phone in Demo mode:
 * scan -> connect -> pick "Red" -> check the exact bytes that would be sent.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DemoModeUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun scanConnectAndSetRed() {
        // Turn Demo mode on if it isn't already.
        val demoSwitch = rule.onNodeWithTag("demoSwitch")
        if (runCatching { demoSwitch.assertIsOff() }.isSuccess) demoSwitch.performClick()

        rule.onNodeWithText("Scan for bulbs").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Demo Bulb (Triones)"), timeoutMillis = 5_000)
        rule.onNodeWithText("Demo Bulb (Triones)").performClick()

        rule.waitUntilAtLeastOneExists(hasText("Connected to Demo Bulb (Triones)"), timeoutMillis = 5_000)

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Red"))
        rule.onNodeWithText("Red").performClick()

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Last command", substring = true))
        rule.onNodeWithText("Last command: 56 FF 00 00 00 F0 AA").assertExists()
    }
}
