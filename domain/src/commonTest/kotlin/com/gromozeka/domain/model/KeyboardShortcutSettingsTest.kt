package com.gromozeka.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class KeyboardShortcutSettingsTest {
    @Test
    fun normalizesMissingAndDuplicateActions() {
        val custom = KeyboardShortcutSettings(
            bindings = listOf(
                KeyboardShortcutBinding(
                    action = KeyboardShortcutAction.PUSH_TO_TALK,
                    scope = KeyboardShortcutScope.FOCUSED,
                    key = KeyboardShortcutKey.SPACE,
                ),
                KeyboardShortcutBinding(
                    action = KeyboardShortcutAction.PUSH_TO_TALK,
                    scope = KeyboardShortcutScope.FOCUSED,
                    key = KeyboardShortcutKey.ESCAPE,
                ),
            )
        ).normalized()

        assertEquals(KeyboardShortcutAction.entries.size, custom.bindings.size)
        assertEquals(
            KeyboardShortcutKey.ESCAPE,
            custom.binding(KeyboardShortcutAction.PUSH_TO_TALK).key,
        )
        assertTrue(custom.binding(KeyboardShortcutAction.EDIT_LAST_USER_MESSAGE).enabled)
    }

    @Test
    fun reportsDuplicateAndDangerousGlobalBindings() {
        val settings = KeyboardShortcutSettings(
            bindings = KeyboardShortcutSettings.defaultBindings().map { binding ->
                when (binding.action) {
                    KeyboardShortcutAction.TOGGLE_LIVE_VOICE -> binding.copy(
                        enabled = true,
                        key = KeyboardShortcutKey.F,
                        modifiers = binding.modifiers,
                    )
                    else -> binding
                }
            }
        )

        val issues = KeyboardShortcutValidator.validate(settings)

        assertTrue(issues.any { it.action == KeyboardShortcutAction.TOGGLE_LIVE_VOICE })
        assertTrue(issues.any { it.action == KeyboardShortcutAction.FIX_CLIPBOARD_TEXT })
    }

    @Test
    fun desktopSettingsWithoutShortcutModelUseCurrentDefaults() {
        val settings = Json { ignoreUnknownKeys = true }
            .decodeFromString<UserDeviceSettings.Desktop>(
                """{"inputSettings":{"globalPttHotkeyEnabled":true}}"""
            )

        assertEquals(
            KeyboardShortcutSettings.defaultBindings(),
            settings.inputSettings.keyboardShortcuts.bindings,
        )
    }
}
