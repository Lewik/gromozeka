package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiSubscriptionConnection
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaPacingPolicy
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaRequest
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaSnapshot
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaWindow
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import java.util.concurrent.ConcurrentHashMap
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

enum class BackgroundAiWorkload(val displayName: String) {
    MEMORY_WRITE("memory write"),
    MEMORY_MAINTENANCE("memory maintenance"),
}

internal fun AiRuntimeAssignment.Purpose.backgroundWorkloadOrNull(): BackgroundAiWorkload? =
    when (this) {
        AiRuntimeAssignment.Purpose.MEMORY_WRITE -> BackgroundAiWorkload.MEMORY_WRITE
        AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE -> BackgroundAiWorkload.MEMORY_MAINTENANCE
        else -> when (fallbackPurpose) {
            AiRuntimeAssignment.Purpose.MEMORY_WRITE -> BackgroundAiWorkload.MEMORY_WRITE
            AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE -> BackgroundAiWorkload.MEMORY_MAINTENANCE
            else -> null
        }
    }

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class BackgroundAiExecutionPacer(
    private val quotaSnapshotService: AiSubscriptionQuotaSnapshotService,
) {
    private val log = KLoggers.logger(this)
    private val states = ConcurrentHashMap<QuotaKey, QuotaState>()
    private val locks = ConcurrentHashMap<QuotaKey, Mutex>()

    fun wrap(
        delegate: AiRuntime,
        runtime: ResolvedAiRuntime,
        workload: BackgroundAiWorkload,
    ): AiRuntime {
        val connection = runtime.connection as? AiSubscriptionConnection ?: return delegate
        if (!connection.quotaPacing.enabled) return delegate
        return PacedAiRuntime(delegate, runtime, workload)
    }

    private suspend fun awaitPermit(
        runtime: ResolvedAiRuntime,
        request: AiRuntimeRequest,
        workload: BackgroundAiWorkload,
    ): QuotaKey {
        val connection = runtime.connection as AiSubscriptionConnection
        val policy = connection.quotaPacing
        val userId = request.options.toolContext[TOOL_CONTEXT_USER_ID]?.toString()?.takeIf(String::isNotBlank)
        val key = QuotaKey(runtime.connection.id.value, userId)
        while (true) {
            when (val readiness = readiness(key, runtime, policy, userId)) {
                is QuotaReadiness.Ready -> {
                    states[key]?.lastLoggedReason = null
                    return key
                }
                is QuotaReadiness.Waiting -> {
                    val state = states.computeIfAbsent(key) { QuotaState() }
                    if (state.lastLoggedReason != readiness.reason) {
                        state.lastLoggedReason = readiness.reason
                        log.info {
                            "Background AI workload is waiting for subscription quota: " +
                                "workload=${workload.displayName} connection=${key.connectionId} " +
                                "reason=${readiness.reason} retryAt=${readiness.retryAt}"
                        }
                    }
                    val delayMillis = (readiness.retryAt.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds())
                        .coerceAtLeast(MINIMUM_RETRY_DELAY_MILLIS)
                    delay(delayMillis)
                }
            }
        }
    }

    private suspend fun readiness(
        key: QuotaKey,
        runtime: ResolvedAiRuntime,
        policy: AiSubscriptionQuotaPacingPolicy,
        userId: String?,
    ): QuotaReadiness = locks.computeIfAbsent(key) { Mutex() }.withLock {
        val state = states.computeIfAbsent(key) { QuotaState() }
        val now = Clock.System.now()
        if (state.callInFlight) {
            return@withLock QuotaReadiness.Waiting(
                reason = "another background call is using the latest quota snapshot",
                retryAt = now + 1.seconds,
            )
        }
        val modelId = runtime.modelConfiguration.providerModelId
        if (
            state.refreshRequired ||
            state.snapshot == null ||
            state.snapshotModelId != modelId ||
            now >= state.nextRefreshAt
        ) {
            if (now < state.nextRefreshAt) {
                return@withLock QuotaReadiness.Waiting(
                    reason = state.lastFailure ?: "quota refresh required after the previous background call",
                    retryAt = state.nextRefreshAt,
                )
            }
            try {
                state.snapshot = quotaSnapshotService.read(
                    AiSubscriptionQuotaRequest(
                        connection = runtime.connection,
                        modelId = modelId,
                        userId = userId,
                    ),
                    forceRefresh = true,
                )
                state.snapshotModelId = modelId
                state.refreshRequired = false
                state.failureCount = 0
                state.lastFailure = null
                state.nextRefreshAt = now + policy.refreshIntervalSeconds.seconds
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.snapshot = null
                state.snapshotModelId = null
                state.refreshRequired = true
                state.failureCount += 1
                state.lastFailure = "quota telemetry unavailable: ${error.message ?: error::class.simpleName}"
                val multiplier = 2.0.pow((state.failureCount - 1).coerceAtMost(MAX_BACKOFF_EXPONENT)).toLong()
                val backoffSeconds = (policy.refreshIntervalSeconds * multiplier).coerceAtMost(MAX_BACKOFF_SECONDS)
                state.nextRefreshAt = now + backoffSeconds.seconds
                return@withLock QuotaReadiness.Waiting(state.lastFailure!!, state.nextRefreshAt)
            }
        }

        val readiness = SubscriptionQuotaPacingEvaluator.evaluate(
            snapshot = checkNotNull(state.snapshot),
            policy = policy,
            now = now,
            refreshAt = state.nextRefreshAt,
        )
        if (readiness is QuotaReadiness.Ready) {
            state.callInFlight = true
            state.refreshRequired = true
        }
        readiness
    }

    private suspend fun markCallFinished(key: QuotaKey) {
        locks.computeIfAbsent(key) { Mutex() }.withLock {
            states.computeIfAbsent(key) { QuotaState() }.apply {
                callInFlight = false
                refreshRequired = true
            }
        }
    }

    private inner class PacedAiRuntime(
        private val delegate: AiRuntime,
        private val runtime: ResolvedAiRuntime,
        private val workload: BackgroundAiWorkload,
    ) : AiRuntime {
        override val capabilities = delegate.capabilities

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            val key = awaitPermit(runtime, request, workload)
            return try {
                delegate.call(request)
            } finally {
                markCallFinished(key)
            }
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow {
            val key = awaitPermit(runtime, request, workload)
            try {
                emitAll(delegate.stream(request))
            } finally {
                markCallFinished(key)
            }
        }
    }

    private data class QuotaKey(
        val connectionId: String,
        val userId: String?,
    )

    private data class QuotaState(
        var snapshot: AiSubscriptionQuotaSnapshot? = null,
        var snapshotModelId: String? = null,
        var nextRefreshAt: Instant = Instant.DISTANT_PAST,
        var refreshRequired: Boolean = true,
        var callInFlight: Boolean = false,
        var failureCount: Int = 0,
        var lastFailure: String? = null,
        @Volatile var lastLoggedReason: String? = null,
    )

    private companion object {
        const val MINIMUM_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_BACKOFF_EXPONENT = 4
        const val MAX_BACKOFF_SECONDS = 15L * 60L
    }
}

internal object SubscriptionQuotaPacingEvaluator {
    fun evaluate(
        snapshot: AiSubscriptionQuotaSnapshot,
        policy: AiSubscriptionQuotaPacingPolicy,
        now: Instant,
        refreshAt: Instant,
    ): QuotaReadiness {
        if (snapshot.unlimited) return QuotaReadiness.Ready
        if (snapshot.usageBlocked) {
            return QuotaReadiness.Waiting(
                reason = "provider reports quota exhausted",
                retryAt = earliestRetry(snapshot, refreshAt, now),
            )
        }

        val decisions = snapshot.windows.map { window -> window.evaluate(policy, now) }
        val blocked = decisions.filterIsInstance<WindowDecision.Waiting>()
        if (blocked.isEmpty()) return QuotaReadiness.Ready
        val limiting = blocked.maxBy { it.requiredRemainingPercent - it.actualRemainingPercent }
        return QuotaReadiness.Waiting(
            reason = "${limiting.displayName} has ${limiting.actualRemainingPercent.formatPercent()}% remaining; " +
                "background work needs ${limiting.requiredRemainingPercent.formatPercent()}%",
            retryAt = minOf(refreshAt, limiting.curveAllowsAt.coerceAtLeast(now + 1.seconds)),
        )
    }

    private fun AiSubscriptionQuotaWindow.evaluate(
        policy: AiSubscriptionQuotaPacingPolicy,
        now: Instant,
    ): WindowDecision {
        if (now >= resetsAt) {
            return WindowDecision.Waiting(displayName, 0.0, 100.0, now + 1.seconds)
        }
        val totalMillis = resetsAt.toEpochMilliseconds() - startedAt.toEpochMilliseconds()
        val remainingMillis = resetsAt.toEpochMilliseconds() - now.toEpochMilliseconds()
        val scheduledRemaining = (remainingMillis.toDouble() / totalMillis.toDouble() * 100.0).coerceIn(0.0, 100.0)
        val requiredRemaining = (scheduledRemaining + policy.reservePercent + policy.minimumHeadroomPercent)
            .coerceAtMost(100.0)
        val actualRemaining = 100.0 - usedPercent
        if (actualRemaining > requiredRemaining) return WindowDecision.Ready

        val allowedRemainingTimeFraction =
            ((actualRemaining - policy.reservePercent - policy.minimumHeadroomPercent) / 100.0).coerceIn(0.0, 1.0)
        val curveAllowsAtMillis = resetsAt.toEpochMilliseconds() -
            (totalMillis.toDouble() * allowedRemainingTimeFraction).toLong()
        return WindowDecision.Waiting(
            displayName = displayName,
            actualRemainingPercent = actualRemaining,
            requiredRemainingPercent = requiredRemaining,
            curveAllowsAt = Instant.fromEpochMilliseconds(curveAllowsAtMillis),
        )
    }

    private fun earliestRetry(
        snapshot: AiSubscriptionQuotaSnapshot,
        refreshAt: Instant,
        now: Instant,
    ): Instant = minOf(
        refreshAt,
        snapshot.windows.minOfOrNull { it.resetsAt } ?: now + 60.seconds,
    ).coerceAtLeast(now + 1.seconds)

    private fun Double.formatPercent(): String =
        ((this * 10.0).toLong() / 10.0).toString()
}

internal sealed interface QuotaReadiness {
    data object Ready : QuotaReadiness

    data class Waiting(
        val reason: String,
        val retryAt: Instant,
    ) : QuotaReadiness
}

private sealed interface WindowDecision {
    data object Ready : WindowDecision

    data class Waiting(
        val displayName: String,
        val actualRemainingPercent: Double,
        val requiredRemainingPercent: Double,
        val curveAllowsAt: Instant,
    ) : WindowDecision
}
