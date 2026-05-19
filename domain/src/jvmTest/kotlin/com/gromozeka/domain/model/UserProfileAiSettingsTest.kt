package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserProfileAiSettingsTest {
    @Test
    fun checksPurposeSupportFromModelSpecCapabilities() {
        val aiSettings = UserProfileAiDefaults.aiSettings()
        val chatModel = aiSettings.modelConfigurations.first { it.id.value == "openai-subscription-gpt-5.5" }
        val speechModel = aiSettings.modelConfigurations.first { it.id.value == "openai-api-gpt-4o-transcribe" }

        assertTrue(aiSettings.supportsPurpose(chatModel, AiRuntimeAssignment.Purpose.DEFAULT_CHAT))
        assertFalse(aiSettings.supportsPurpose(speechModel, AiRuntimeAssignment.Purpose.DEFAULT_CHAT))
        assertTrue(aiSettings.supportsPurpose(speechModel, AiRuntimeAssignment.Purpose.SPEECH_TO_TEXT))
    }

    @Test
    fun rejectsAssignmentToModelWithoutRequiredCapability() {
        val speechSelection = AiRuntimeSelection(AiModelConfiguration.Id("openai-api-gpt-4o-transcribe"))

        assertFailsWith<IllegalArgumentException> {
            UserProfile.AiSettings(
                connections = UserProfileAiDefaults.connections(),
                modelSpecs = UserProfileAiDefaults.modelSpecs(),
                modelConfigurations = UserProfileAiDefaults.modelConfigurations(),
                runtimeAssignments = UserProfileAiDefaults.runtimeAssignments().map { assignment ->
                    if (assignment.purpose == AiRuntimeAssignment.Purpose.DEFAULT_CHAT) {
                        assignment.copy(selection = speechSelection)
                    } else {
                        assignment
                    }
                },
            )
        }
    }
}
