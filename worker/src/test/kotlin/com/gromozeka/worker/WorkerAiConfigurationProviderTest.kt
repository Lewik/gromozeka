package com.gromozeka.worker

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerAiConfigurationProviderTest {
    @Test
    fun `catalog is unavailable until Server synchronization`() {
        val provider = WorkerAiConfigurationProvider()

        assertFailsWith<IllegalStateException> {
            provider.snapshot
        }
    }

    @Test
    fun `synchronizes catalog and resolves an enabled runtime`() {
        val provider = WorkerAiConfigurationProvider()
        val snapshot = snapshot(revision = 3)

        provider.synchronize(snapshot)

        assertEquals(snapshot, provider.snapshot)
        assertEquals(
            snapshot.catalog.connections.single(),
            provider.resolveAiRuntime(AiRuntimeSelection(modelConfigurationId)).connection,
        )
    }

    @Test
    fun `rejects catalog revision regression`() {
        val provider = WorkerAiConfigurationProvider()
        provider.synchronize(snapshot(revision = 3))

        assertFailsWith<IllegalArgumentException> {
            provider.synchronize(snapshot(revision = 2))
        }
    }

    private fun snapshot(revision: Long): AiCatalogSnapshot {
        val connection = AiConnection.OpenAiApi(
            id = AiConnection.Id("worker-openai"),
            displayName = "Worker OpenAI",
            enabled = true,
        )
        val configuration = AiModelConfiguration(
            id = modelConfigurationId,
            connectionId = connection.id,
            providerModelId = "gpt-test",
            displayName = "GPT Test",
        )
        return AiCatalogSnapshot(
            catalog = AiCatalog(
                connections = listOf(connection),
                modelSpecs = listOf(
                    AiModelSpec(
                        id = configuration.providerModelId,
                        provider = AiProvider.OPENAI,
                        capabilities = AiModelCapability.entries.toSet(),
                        limits = AiModelSpec.Limits(
                            textGeneration = AiModelSpec.Limits.TextGeneration(
                                contextWindowTokens = 1_024,
                            ),
                            embeddings = AiModelSpec.Limits.Embeddings(dimensions = 8),
                        ),
                    )
                ),
                modelConfigurations = listOf(configuration),
                runtimeAssignments = AiRuntimeAssignment.Purpose.entries
                    .filter(AiRuntimeAssignment.Purpose::requiresExplicitAssignment)
                    .map {
                        AiRuntimeAssignment(
                            purpose = it,
                            selection = AiRuntimeSelection(configuration.id),
                        )
                    },
                defaultAgentId = AgentDefinition.Id("test-agent"),
            ),
            revision = revision,
        )
    }

    private companion object {
        val modelConfigurationId = AiModelConfiguration.Id("worker-model")
    }
}
