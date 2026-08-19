package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaObservation
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaRequest
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaSnapshot
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaWindow
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiSubscriptionQuotaProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AiSubscriptionQuotaSnapshotServiceTest {
    private val connection = AiConnection.OpenAiSubscription(
        id = AiConnection.Id("subscription"),
        displayName = "Subscription",
        enabled = true,
    )
    private val configuration = AiModelConfiguration(
        id = AiModelConfiguration.Id("model"),
        connectionId = connection.id,
        providerModelId = "gpt-test",
        displayName = "Test model",
    )

    @Test
    fun `cached quota is shared and provider failures preserve a stale snapshot`() = runBlocking {
        var reads = 0
        var fail = false
        val provider = object : AiSubscriptionQuotaProvider {
            override suspend fun read(request: AiSubscriptionQuotaRequest): AiSubscriptionQuotaSnapshot {
                reads++
                if (fail) error("provider unavailable")
                return snapshot()
            }
        }
        val snapshotService = AiSubscriptionQuotaSnapshotService(provider)
        val applicationService = AiSubscriptionQuotaApplicationService(
            aiConfigurationProvider = FixedConfigurationProvider(catalog()),
            snapshotService = snapshotService,
        )

        val first = applicationService.read(User.Id("user"), configuration.id, forceRefresh = false)
        val cached = applicationService.read(User.Id("user"), configuration.id, forceRefresh = false)

        assertEquals(AiSubscriptionQuotaObservation.Status.FRESH, first.status)
        assertEquals(AiSubscriptionQuotaObservation.Status.FRESH, cached.status)
        assertEquals(1, reads)

        fail = true
        val stale = applicationService.read(User.Id("user"), configuration.id, forceRefresh = true)

        assertEquals(AiSubscriptionQuotaObservation.Status.STALE, stale.status)
        assertEquals(first.snapshot, stale.snapshot)
        assertEquals(2, reads)
    }

    @Test
    fun `concurrent forced refreshes share one provider acquisition`() = runBlocking {
        var reads = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = object : AiSubscriptionQuotaProvider {
            override suspend fun read(request: AiSubscriptionQuotaRequest): AiSubscriptionQuotaSnapshot {
                reads++
                started.complete(Unit)
                release.await()
                return snapshot()
            }
        }
        val service = AiSubscriptionQuotaSnapshotService(provider)
        val request = AiSubscriptionQuotaRequest(
            connection = connection,
            modelId = configuration.providerModelId,
            userId = "user",
        )

        val first = async { service.read(request, forceRefresh = true) }
        started.await()
        val second = async { service.read(request, forceRefresh = true) }
        release.complete(Unit)
        awaitAll(first, second)

        assertEquals(1, reads)
    }

    private fun snapshot() = AiSubscriptionQuotaSnapshot(
        connectionId = connection.id,
        observedAt = Instant.parse("2026-08-19T12:00:00Z"),
        windows = listOf(
            AiSubscriptionQuotaWindow(
                id = "five-hour",
                displayName = "5 hour",
                usedPercent = 25.0,
                startedAt = Instant.parse("2026-08-19T10:00:00Z"),
                resetsAt = Instant.parse("2026-08-19T15:00:00Z"),
            )
        ),
    )

    private fun catalog(): AiCatalog {
        val modelSpec = AiModelSpec(
            id = configuration.providerModelId,
            provider = AiProvider.OPENAI,
            capabilities = AiModelCapability.entries.toSet(),
            limits = AiModelSpec.Limits(
                textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 1_000),
                embeddings = AiModelSpec.Limits.Embeddings(dimensions = 3),
            ),
        )
        val selection = AiRuntimeSelection(configuration.id)
        return AiCatalog(
            connections = listOf(connection),
            modelSpecs = listOf(modelSpec),
            modelConfigurations = listOf(configuration),
            runtimeAssignments = AiRuntimeAssignment.Purpose.entries
                .filter(AiRuntimeAssignment.Purpose::requiresExplicitAssignment)
                .map { AiRuntimeAssignment(it, selection) },
            defaultAgentId = AgentDefinition.Id("agent"),
        )
    }

    private class FixedConfigurationProvider(catalog: AiCatalog) : AiConfigurationProvider {
        override val snapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(AiCatalogSnapshot(catalog, revision = 1))
        override val snapshot = requireNotNull(snapshotFlow.value)

        override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime =
            error("Not needed by this test")
    }
}
