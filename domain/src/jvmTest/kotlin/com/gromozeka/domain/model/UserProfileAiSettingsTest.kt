package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.SettingsProvider
import kotlin.test.assertEquals
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
        val embeddingModel = aiSettings.modelConfigurations.first { it.id.value == "openai-api-text-embedding-3-large" }

        assertTrue(aiSettings.supportsPurpose(chatModel, AiRuntimeAssignment.Purpose.DEFAULT_CHAT))
        assertFalse(aiSettings.supportsPurpose(speechModel, AiRuntimeAssignment.Purpose.DEFAULT_CHAT))
        assertTrue(aiSettings.supportsPurpose(speechModel, AiRuntimeAssignment.Purpose.SPEECH_TO_TEXT))
        assertTrue(aiSettings.supportsPurpose(embeddingModel, AiRuntimeAssignment.Purpose.MEMORY_EMBEDDINGS))
        assertFalse(aiSettings.supportsPurpose(chatModel, AiRuntimeAssignment.Purpose.MEMORY_EMBEDDINGS))
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

    @Test
    fun resolvesOptionalMemoryStageAssignmentThroughFallbackPurpose() {
        val provider = testSettingsProvider(UserProfile())
        val writeSelection = provider.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE)

        assertEquals(
            writeSelection,
            provider.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_ROUTER),
        )
        assertEquals(
            writeSelection,
            provider.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_CLAIM_EXTRACTOR),
        )
    }

    @Test
    fun acceptsExplicitOptionalMemoryStageAssignment() {
        val stageSelection = AiRuntimeSelection(AiModelConfiguration.Id("openai-api-gpt-4o-mini"))
        val profile = UserProfile(
            aiSettings = UserProfileAiDefaults.aiSettings().copy(
                runtimeAssignments = UserProfileAiDefaults.runtimeAssignments() +
                    AiRuntimeAssignment(AiRuntimeAssignment.Purpose.MEMORY_WRITE_ROUTER, stageSelection),
            )
        )

        assertEquals(
            stageSelection,
            testSettingsProvider(profile).runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_ROUTER),
        )
    }

    private fun testSettingsProvider(profile: UserProfile): SettingsProvider =
        object : SettingsProvider {
            override val userProfile: UserProfile = profile
            override val userDeviceSettings: UserDeviceSettings = UserDeviceSettings.Desktop()
            override val mode: AppMode = AppMode.TEST
            override val homeDirectory: String = "/tmp/gromozeka-test"
        }
}
