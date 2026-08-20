package com.gromozeka.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.gromozeka.domain.model.KeyboardShortcutAction
import com.gromozeka.domain.model.KeyboardShortcutKey
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.presentation.ui.UiTestTag
import kotlin.test.Test

class KeyboardShortcutSettingsE2eTest {
    @Test
    fun recordsAndPersistsKeyboardShortcut() = runGromozekaUiTest("keyboard-shortcut-settings") { client ->
        onNodeWithTag(UiTestTag.SettingsTab.value).performClick()
        waitForTag(UiTestTag.SettingsSectionTab("Keyboard"))
        onNodeWithTag(UiTestTag.SettingsSectionTab("Keyboard").value).performClick()
        waitForTag(UiTestTag.KeyboardShortcuts)

        val captureTag = UiTestTag.KeyboardShortcutCapture(KeyboardShortcutAction.PUSH_TO_TALK.name)
        onNodeWithTag(captureTag.value).performClick().performKeyInput {
            pressKey(Key.F8)
        }

        waitUntil(timeoutMillis = 30_000) {
            val desktopSettings = client.components.settingsService.settings.userDeviceSettings
                as? UserDeviceSettings.Desktop
            desktopSettings?.inputSettings?.keyboardShortcuts
                ?.binding(KeyboardShortcutAction.PUSH_TO_TALK)
                ?.key == KeyboardShortcutKey.F8
        }
        onNodeWithTag(captureTag.value).assertTextContains("F8")
    }
}
