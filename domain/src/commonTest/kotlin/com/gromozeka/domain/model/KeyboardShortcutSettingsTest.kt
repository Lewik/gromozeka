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
    fun reportsDuplicateBindings() {
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
    fun profileWithoutShortcutModelUsesCurrentDefaults() {
        val settings = Json { ignoreUnknownKeys = true }
            .decodeFromString<UserProfile>("{}")

        assertEquals(
            KeyboardShortcutSettings.defaultBindings(),
            settings.keyboardShortcuts.bindings,
        )
        assertTrue(
            settings.keyboardShortcuts.bindings.all {
                it.scope == KeyboardShortcutScope.FOCUSED
            }
        )
        assertTrue(
            KeyboardShortcutScope.GLOBAL in
                KeyboardShortcutAction.FIX_CLIPBOARD_TEXT.supportedScopes
        )
        assertTrue(
            KeyboardShortcutScope.GLOBAL in
                KeyboardShortcutAction.TRANSLATE_CLIPBOARD_TEXT.supportedScopes
        )
    }
    @Test
    fun rejectsEqualCombinationsAcrossScopesEvenWhenDisabled() {
        val defaults = KeyboardShortcutSettings.defaultBindings()
        val source = defaults.first { it.action == KeyboardShortcutAction.FIX_CLIPBOARD_TEXT }
        val settings = KeyboardShortcutSettings(bindings = defaults.map {
            if (it.action == KeyboardShortcutAction.TOGGLE_LIVE_VOICE) {
                it.copy(key = source.key, modifiers = source.modifiers, scope = KeyboardShortcutScope.GLOBAL)
            } else it
        })
        val errors = KeyboardShortcutValidator.validate(settings)
        assertEquals(setOf(source.action, KeyboardShortcutAction.TOGGLE_LIVE_VOICE), errors.map { it.action }.toSet())
    }

    @Test
    fun reservesOnlyEnterAndShiftEnter() {
        for (modifiers in listOf(emptySet(), setOf(KeyboardShortcutModifier.SHIFT))) {
            val settings = KeyboardShortcutSettings(bindings = KeyboardShortcutSettings.defaultBindings().map {
                if (it.action == KeyboardShortcutAction.PUSH_TO_TALK) {
                    it.copy(key = KeyboardShortcutKey.ENTER, modifiers = modifiers)
                } else it
            })
            assertTrue(KeyboardShortcutValidator.validate(settings).any {
                it.action == KeyboardShortcutAction.PUSH_TO_TALK && it.severity == KeyboardShortcutValidationSeverity.ERROR
            })
        }
        val settings = KeyboardShortcutSettings(bindings = KeyboardShortcutSettings.defaultBindings().map {
            if (it.action == KeyboardShortcutAction.PUSH_TO_TALK) {
                it.copy(key = KeyboardShortcutKey.F4, modifiers = setOf(KeyboardShortcutModifier.ALT), scope = KeyboardShortcutScope.GLOBAL)
            } else it
        })
        assertTrue(KeyboardShortcutValidator.validate(settings).isEmpty())
    }

    @Test
    fun webSettingsRoundTripPreservesGlobalScopeAndEnterAction() {
        val shortcuts = KeyboardShortcutSettings(
            enterKeyAction = EnterKeyAction.SEND_MESSAGE,
            bindings = KeyboardShortcutSettings.defaultBindings().map {
                if (it.action == KeyboardShortcutAction.PUSH_TO_TALK) it.copy(scope = KeyboardShortcutScope.GLOBAL) else it
            },
        )
        val settings = Settings(userProfile = UserProfile(keyboardShortcuts = shortcuts), userDeviceSettings = UserDeviceSettings.Web())
        val restored = Json.decodeFromString<Settings>(Json.encodeToString(settings))
        assertEquals(shortcuts, restored.userProfile.keyboardShortcuts)
        assertEquals(EnterKeyAction.NEW_LINE, KeyboardShortcutSettings().enterKeyAction)
    }

}
