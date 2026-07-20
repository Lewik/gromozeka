package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsServiceTest {
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
