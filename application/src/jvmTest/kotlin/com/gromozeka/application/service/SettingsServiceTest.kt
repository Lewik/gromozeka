package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.KeyboardShortcutSettings
import com.gromozeka.domain.model.KeyboardShortcutAction
import com.gromozeka.domain.model.KeyboardShortcutKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsServiceTest {
    @Test
    fun invalidShortcutIsRejectedBeforeUpdatingSettings() {
        val service = SettingsService()
        val before = service.settings
        val shortcuts = KeyboardShortcutSettings(bindings = KeyboardShortcutSettings.defaultBindings().map {
            if (it.action == KeyboardShortcutAction.PUSH_TO_TALK) it.copy(key = KeyboardShortcutKey.ENTER) else it
        })
        assertFailsWith<IllegalArgumentException> {
            service.saveSettings(Settings(userProfile = UserProfile(keyboardShortcuts = shortcuts)))
        }
        assertEquals(before, service.settings)
    }

    @Test
    fun parsesRuntimeEnabledAiConnectionIds() {
        assertEquals(
            linkedSetOf(
                AiConnection.Id("openai-subscription"),
                AiConnection.Id("openai-api"),
            ),
            SettingsService.parseRuntimeEnabledAiConnectionIds("openai-subscription, openai-api"),
        )
    }

    @Test
    fun rejectsMalformedRuntimeEnabledAiConnectionIds() {
        assertFailsWith<IllegalArgumentException> {
            SettingsService.parseRuntimeEnabledAiConnectionIds("")
        }
        assertFailsWith<IllegalArgumentException> {
            SettingsService.parseRuntimeEnabledAiConnectionIds("openai-subscription,,openai-api")
        }
        assertFailsWith<IllegalArgumentException> {
            SettingsService.parseRuntimeEnabledAiConnectionIds("openai-subscription,openai-subscription")
        }
    }
}
