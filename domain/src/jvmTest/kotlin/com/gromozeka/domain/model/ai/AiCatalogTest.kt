package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiCatalogTest {
    @Test
    fun checksPurposeSupportFromModelSpecCapabilities() {
        val catalog = testCatalog()
        val chatModel = catalog.modelConfigurations.first { it.id == CHAT_CONFIGURATION_ID }
        val speechModel = catalog.modelConfigurations.first { it.id == SPEECH_CONFIGURATION_ID }
        val embeddingModel = catalog.modelConfigurations.first { it.id == EMBEDDING_CONFIGURATION_ID }

        assertTrue(catalog.supportsPurpose(chatModel, AiRuntimeAssignment.Purpose.DEFAULT_CHAT))
        assertFalse(catalog.supportsPurpose(speechModel, AiRuntimeAssignment.Purpose.DEFAULT_CHAT))
        assertTrue(catalog.supportsPurpose(speechModel, AiRuntimeAssignment.Purpose.SPEECH_TO_TEXT))
        assertTrue(catalog.supportsPurpose(embeddingModel, AiRuntimeAssignment.Purpose.MEMORY_EMBEDDINGS))
        assertFalse(catalog.supportsPurpose(chatModel, AiRuntimeAssignment.Purpose.MEMORY_EMBEDDINGS))
    }

    @Test
    fun runtimeEnvironmentCanEnableAStoredDisabledConnection() {
        val catalog = testCatalog(connectionEnabled = false)
        val chatModel = catalog.modelConfigurations.first { it.id == CHAT_CONFIGURATION_ID }
        val snapshot = AiCatalogSnapshot(
            catalog = catalog,
            revision = 2,
            runtimeEnabledConnectionIds = setOf(CONNECTION_ID),
        )

        assertFalse(catalog.supportsPurpose(chatModel, AiRuntimeAssignment.Purpose.DEFAULT_CHAT))
        assertTrue(snapshot.supportsPurpose(chatModel, AiRuntimeAssignment.Purpose.DEFAULT_CHAT))
        assertFalse(catalog.connections.single().enabled)
    }

    @Test
    fun rejectsAssignmentToModelWithoutRequiredCapability() {
        val catalog = testCatalog()

        assertFailsWith<IllegalArgumentException> {
            catalog.copy(
                runtimeAssignments = catalog.runtimeAssignments.map { assignment ->
                    if (assignment.purpose == AiRuntimeAssignment.Purpose.DEFAULT_CHAT) {
                        assignment.copy(selection = AiRuntimeSelection(SPEECH_CONFIGURATION_ID))
                    } else {
                        assignment
                    }
                },
            )
        }
    }

    @Test
    fun resolvesOptionalMemoryStageAssignmentThroughFallbackPurpose() {
        val catalog = testCatalog()
        val writeSelection = catalog.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE)

        assertEquals(
            writeSelection,
            catalog.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_ROUTER),
        )
        assertEquals(
            writeSelection,
            catalog.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_CLAIM_EXTRACTOR),
        )
    }

    @Test
    fun acceptsExplicitOptionalMemoryStageAssignment() {
        val catalog = testCatalog()
        val stageSelection = AiRuntimeSelection(CHAT_CONFIGURATION_ID)
        val updated = catalog.copy(
            runtimeAssignments = catalog.runtimeAssignments +
                AiRuntimeAssignment(AiRuntimeAssignment.Purpose.MEMORY_WRITE_ROUTER, stageSelection),
        )

        assertEquals(
            stageSelection,
            updated.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_ROUTER),
        )
    }

    @Test
    fun rejectsClaudeCodeWebToolsBackedByAnotherProvider() {
        val catalog = testCatalog()

        assertFailsWith<IllegalArgumentException> {
            catalog.copy(
                webTools = AiWebToolConfiguration(
                    claudeCode = AiWebToolConfiguration.ClaudeCode(
                        modelConfigurationId = CHAT_CONFIGURATION_ID,
                        searchEnabled = true,
                    )
                )
            )
        }
    }

    private fun testCatalog(connectionEnabled: Boolean = true): AiCatalog {
        val configurations = listOf(
            configuration(CHAT_CONFIGURATION_ID, "chat"),
            configuration(EMBEDDING_CONFIGURATION_ID, "embedding"),
            configuration(SPEECH_CONFIGURATION_ID, "speech"),
            configuration(TTS_CONFIGURATION_ID, "tts"),
        )
        val selectionsByCapability = mapOf(
            AiModelCapability.TEXT_GENERATION to AiRuntimeSelection(CHAT_CONFIGURATION_ID),
            AiModelCapability.EMBEDDINGS to AiRuntimeSelection(EMBEDDING_CONFIGURATION_ID),
            AiModelCapability.SPEECH_TO_TEXT to AiRuntimeSelection(SPEECH_CONFIGURATION_ID),
            AiModelCapability.TEXT_TO_SPEECH to AiRuntimeSelection(TTS_CONFIGURATION_ID),
        )
        return AiCatalog(
            connections = listOf(
                AiConnection.OpenAiApi(
                    id = CONNECTION_ID,
                    displayName = "Test OpenAI",
                    enabled = connectionEnabled,
                )
            ),
            modelSpecs = listOf(
                spec("chat", AiModelCapability.TEXT_GENERATION),
                spec("embedding", AiModelCapability.EMBEDDINGS),
                spec("speech", AiModelCapability.SPEECH_TO_TEXT),
                spec("tts", AiModelCapability.TEXT_TO_SPEECH),
            ),
            modelConfigurations = configurations,
            runtimeAssignments = AiRuntimeAssignment.Purpose.entries
                .filter { it.requiresExplicitAssignment }
                .map { purpose ->
                    val capability = purpose.requiredCapabilities.single()
                    AiRuntimeAssignment(purpose, selectionsByCapability.getValue(capability))
                },
            defaultAgentId = AgentDefinition.Id("test-agent"),
        )
    }

    private fun configuration(
        id: AiModelConfiguration.Id,
        providerModelId: String,
    ): AiModelConfiguration =
        AiModelConfiguration(
            id = id,
            connectionId = CONNECTION_ID,
            providerModelId = providerModelId,
            displayName = providerModelId,
        )

    private fun spec(
        modelId: String,
        capability: AiModelCapability,
    ): AiModelSpec =
        AiModelSpec(
            id = modelId,
            provider = AiProvider.OPENAI,
            capabilities = setOf(capability),
            limits = when (capability) {
                AiModelCapability.TEXT_GENERATION -> AiModelSpec.Limits(
                    textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 128_000)
                )
                AiModelCapability.EMBEDDINGS -> AiModelSpec.Limits(
                    embeddings = AiModelSpec.Limits.Embeddings(dimensions = 1_536)
                )
                else -> AiModelSpec.Limits()
            },
        )

    private companion object {
        val CONNECTION_ID = AiConnection.Id("test-openai")
        val CHAT_CONFIGURATION_ID = AiModelConfiguration.Id("test-chat")
        val EMBEDDING_CONFIGURATION_ID = AiModelConfiguration.Id("test-embedding")
        val SPEECH_CONFIGURATION_ID = AiModelConfiguration.Id("test-speech")
        val TTS_CONFIGURATION_ID = AiModelConfiguration.Id("test-tts")
    }
}
