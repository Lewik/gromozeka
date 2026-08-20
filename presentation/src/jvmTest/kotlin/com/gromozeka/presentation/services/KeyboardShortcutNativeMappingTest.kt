package com.gromozeka.presentation.services

import com.gromozeka.domain.model.KeyboardShortcutKey
import com.gromozeka.domain.model.KeyboardShortcutAction
import com.gromozeka.domain.model.KeyboardShortcutModifier
import com.gromozeka.domain.model.KeyboardShortcutSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyboardShortcutNativeMappingTest {
    @Test
    fun mapsWindowsCopilotChordFunctionKey() {
        assertEquals(0x86, KeyboardShortcutKey.F23.windowsVirtualKey())
        assertEquals("F23", KeyboardShortcutKey.F23.x11KeyName())
    }

    @Test
    fun registersMacNormalShortcutWithoutInputMonitoringPermission() {
        if (!System.getProperty("os.name").contains("mac", ignoreCase = true)) return
        val controller = MacOsGlobalHotkeyController()
        val settings = KeyboardShortcutSettings(
            bindings = KeyboardShortcutSettings.defaultBindings().map { binding ->
                if (binding.action == KeyboardShortcutAction.FIX_CLIPBOARD_TEXT) {
                    binding.copy(
                        key = KeyboardShortcutKey.F17,
                        modifiers = setOf(
                            KeyboardShortcutModifier.CONTROL,
                            KeyboardShortcutModifier.ALT,
                            KeyboardShortcutModifier.META,
                        ),
                        enabled = true,
                    )
                } else {
                    binding.copy(enabled = false)
                }
            }
        )
        try {
            controller.applySettings(settings) {}
            assertTrue(controller.state.value.available, controller.state.value.message)
            assertTrue(controller.state.value.bindingErrors.isEmpty(), controller.state.value.bindingErrors.toString())
        } finally {
            controller.cleanup()
        }
    }
}
