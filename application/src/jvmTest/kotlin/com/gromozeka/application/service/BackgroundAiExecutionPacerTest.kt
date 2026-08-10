package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaPacingPolicy
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaRequest
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaSnapshot
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaWindow
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiSubscriptionQuotaProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackgroundAiExecutionPacerTest {
    private val connectionId = AiConnection.Id("subscription")
    private val policy = AiSubscriptionQuotaPacingPolicy(
        reservePercent = 15.0,
        minimumHeadroomPercent = 2.0,
        refreshIntervalSeconds = 60,
    )
    private val startedAt = Instant.parse("2026-08-10T00:00:00Z")
    private val resetsAt = Instant.parse("2026-08-10T05:00:00Z")

    @Test
    fun `background call is allowed only above protected spending curve`() {
        val now = Instant.parse("2026-08-10T02:30:00Z")

        assertIs<QuotaReadiness.Ready>(
            evaluate(usedPercent = 20.0, now = now)
        )
        assertIs<QuotaReadiness.Waiting>(
            evaluate(usedPercent = 40.0, now = now)
        )
    }

    @Test
    fun `reserve prevents background work at start of fresh window`() {
        val decision = evaluate(usedPercent = 0.0, now = startedAt)

        assertIs<QuotaReadiness.Waiting>(decision)
    }

    @Test
    fun `waiting decision identifies when protected curve catches current remaining quota`() {
        val now = Instant.parse("2026-08-10T02:30:00Z")
        val decision = SubscriptionQuotaPacingEvaluator.evaluate(
            snapshot = finiteSnapshot(usedPercent = 40.0),
            policy = policy,
            now = now,
            refreshAt = resetsAt,
        )

        assertIs<QuotaReadiness.Waiting>(decision)
        assertEquals(Instant.parse("2026-08-10T02:51:00Z"), decision.retryAt)
    }

    @Test
    fun `unlimited quota is allowed and provider block is denied`() {
        assertIs<QuotaReadiness.Ready>(
            SubscriptionQuotaPacingEvaluator.evaluate(
                snapshot = AiSubscriptionQuotaSnapshot(
                    connectionId = connectionId,
                    observedAt = startedAt,
                    windows = emptyList(),
                    unlimited = true,
                ),
                policy = policy,
                now = startedAt,
                refreshAt = resetsAt,
            )
        )
        assertIs<QuotaReadiness.Waiting>(
            SubscriptionQuotaPacingEvaluator.evaluate(
                snapshot = AiSubscriptionQuotaSnapshot(
                    connectionId = connectionId,
                    observedAt = startedAt,
                    windows = emptyList(),
                    usageBlocked = true,
                ),
                policy = policy,
                now = startedAt,
                refreshAt = resetsAt,
            )
        )
    }

    @Test
    fun `only background memory purposes are paced`() {
        assertEquals(
            BackgroundAiWorkload.MEMORY_WRITE,
            AiRuntimeAssignment.Purpose.MEMORY_WRITE_CLAIM_EXTRACTOR.backgroundWorkloadOrNull(),
        )
        assertEquals(
            BackgroundAiWorkload.MEMORY_MAINTENANCE,
            AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE_REPAIR_PLANNER.backgroundWorkloadOrNull(),
        )
        assertEquals(null, AiRuntimeAssignment.Purpose.MEMORY_READ_PLANNER.backgroundWorkloadOrNull())
        assertEquals(null, AiRuntimeAssignment.Purpose.DEFAULT_CHAT.backgroundWorkloadOrNull())
    }

    @Test
    fun `one quota snapshot permits only one concurrent background call`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val activeCalls = AtomicInteger()
        val maximumActiveCalls = AtomicInteger()
        val quotaReads = AtomicInteger()
        val runtime = resolvedRuntime()
        val quotaProvider = object : AiSubscriptionQuotaProvider {
            override suspend fun read(request: AiSubscriptionQuotaRequest): AiSubscriptionQuotaSnapshot {
                quotaReads.incrementAndGet()
                val now = kotlin.time.Clock.System.now()
                return AiSubscriptionQuotaSnapshot(
                    connectionId = request.connection.id,
                    observedAt = now,
                    windows = listOf(
                        AiSubscriptionQuotaWindow(
                            id = "five_hour",
                            displayName = "5 hour",
                            usedPercent = 0.0,
                            startedAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - 14_400_000),
                            resetsAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 3_600_000),
                        )
                    ),
                )
            }
        }
        val delegate = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                val current = activeCalls.incrementAndGet()
                maximumActiveCalls.accumulateAndGet(current, ::maxOf)
                entered.complete(Unit)
                try {
                    release.await()
                    return AiRuntimeResponse(messages = emptyList())
                } finally {
                    activeCalls.decrementAndGet()
                }
            }

            override fun stream(request: AiRuntimeRequest) = flowOf(AiRuntimeResponse(messages = emptyList()))
        }
        val paced = BackgroundAiExecutionPacer(quotaProvider).wrap(
            delegate = delegate,
            runtime = runtime,
            workload = BackgroundAiWorkload.MEMORY_WRITE,
        )
        val request = AiRuntimeRequest(systemPrompts = emptyList(), messages = emptyList())

        val first = launch { paced.call(request) }
        entered.await()
        val second = launch { paced.call(request) }
        kotlinx.coroutines.delay(100)

        assertEquals(1, activeCalls.get())
        assertEquals(1, maximumActiveCalls.get())
        assertEquals(1, quotaReads.get())

        release.complete(Unit)
        first.join()
        second.cancelAndJoin()
    }

    private fun evaluate(
        usedPercent: Double,
        now: Instant,
    ): QuotaReadiness = SubscriptionQuotaPacingEvaluator.evaluate(
        snapshot = finiteSnapshot(usedPercent),
        policy = policy,
        now = now,
        refreshAt = resetsAt,
    )

    private fun finiteSnapshot(usedPercent: Double) = AiSubscriptionQuotaSnapshot(
        connectionId = connectionId,
        observedAt = startedAt,
        windows = listOf(
            AiSubscriptionQuotaWindow(
                id = "five_hour",
                displayName = "5 hour",
                usedPercent = usedPercent,
                startedAt = startedAt,
                resetsAt = resetsAt,
            )
        ),
    )

    private fun resolvedRuntime(): ResolvedAiRuntime {
        val connection = AiConnection.OpenAiSubscription(
            id = connectionId,
            displayName = "Subscription",
            enabled = true,
            quotaPacing = policy,
        )
        val modelConfiguration = AiModelConfiguration(
            id = AiModelConfiguration.Id("model"),
            connectionId = connection.id,
            providerModelId = "gpt-test",
            displayName = "Test model",
        )
        return ResolvedAiRuntime(
            connection = connection,
            modelConfiguration = modelConfiguration,
            modelSpec = AiModelSpec(
                id = modelConfiguration.providerModelId,
                provider = AiProvider.OPENAI,
                capabilities = setOf(AiModelCapability.TEXT_GENERATION),
                limits = AiModelSpec.Limits(
                    textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 1_000),
                ),
            ),
        )
    }
}
