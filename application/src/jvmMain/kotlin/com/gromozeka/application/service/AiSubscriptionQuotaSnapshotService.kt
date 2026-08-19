package com.gromozeka.application.service

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiSubscriptionConnection
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaObservation
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaRequest
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaSnapshot
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiSubscriptionQuotaProvider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class AiSubscriptionQuotaSnapshotService(
    private val provider: AiSubscriptionQuotaProvider,
) {
    private val snapshots = ConcurrentHashMap<CacheKey, CachedSnapshot>()
    private val locks = ConcurrentHashMap<CacheKey, Mutex>()

    suspend fun read(
        request: AiSubscriptionQuotaRequest,
        forceRefresh: Boolean = false,
    ): AiSubscriptionQuotaSnapshot {
        val key = request.cacheKey()
        val now = Clock.System.now()
        val cachedAtStart = snapshots[key]
        if (!forceRefresh) {
            cachedAtStart?.takeIf { now < it.loadedAt + CACHE_TTL }?.let { return it.snapshot }
        }
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            val lockedNow = Clock.System.now()
            snapshots[key]?.let { cached ->
                val refreshedWhileWaiting = cachedAtStart !== cached
                if (refreshedWhileWaiting || (!forceRefresh && lockedNow < cached.loadedAt + CACHE_TTL)) {
                    return@withLock cached.snapshot
                }
            }
            provider.read(request).also { snapshot ->
                snapshots[key] = CachedSnapshot(snapshot, lockedNow)
            }
        }
    }

    fun latest(request: AiSubscriptionQuotaRequest): AiSubscriptionQuotaSnapshot? =
        snapshots[request.cacheKey()]?.snapshot

    private fun AiSubscriptionQuotaRequest.cacheKey(): CacheKey = CacheKey(
        connectionId = connection.id.value,
        connectionKind = connection.kind.name,
        executionTarget = connection.executionTarget.cacheIdentity(),
        modelId = modelId,
        userId = userId,
    )

    private fun AiExecutionTarget.cacheIdentity(): String = when (this) {
        AiExecutionTarget.Server -> "server"
        is AiExecutionTarget.Worker -> "worker:$workerId"
    }

    private data class CacheKey(
        val connectionId: String,
        val connectionKind: String,
        val executionTarget: String,
        val modelId: String?,
        val userId: String?,
    )

    private data class CachedSnapshot(
        val snapshot: AiSubscriptionQuotaSnapshot,
        val loadedAt: kotlin.time.Instant,
    )

    private companion object {
        val CACHE_TTL = 60.seconds
    }
}

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class AiSubscriptionQuotaApplicationService(
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val snapshotService: AiSubscriptionQuotaSnapshotService,
) {
    suspend fun read(
        userId: User.Id,
        modelConfigurationId: AiModelConfiguration.Id,
        forceRefresh: Boolean,
    ): AiSubscriptionQuotaObservation {
        val configuration = aiConfigurationProvider.catalog.modelConfigurations.singleOrNull {
            it.id == modelConfigurationId
        } ?: error("AI model configuration not found: ${modelConfigurationId.value}")
        val connection = requireNotNull(aiConfigurationProvider.catalog.connectionFor(configuration)) {
            "AI connection not found: ${configuration.connectionId.value}"
        }
        val metadata = ObservationMetadata(configuration, connection)
        if (connection !is AiSubscriptionConnection) {
            return metadata.observation(AiSubscriptionQuotaObservation.Status.NOT_SUPPORTED)
        }
        val request = AiSubscriptionQuotaRequest(
            connection = connection,
            modelId = configuration.providerModelId,
            userId = userId.value,
        )
        return try {
            metadata.observation(
                status = AiSubscriptionQuotaObservation.Status.FRESH,
                snapshot = snapshotService.read(request, forceRefresh),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            snapshotService.latest(request)?.let { stale ->
                metadata.observation(AiSubscriptionQuotaObservation.Status.STALE, stale)
            } ?: metadata.observation(AiSubscriptionQuotaObservation.Status.UNAVAILABLE)
        }
    }

    private data class ObservationMetadata(
        val configuration: AiModelConfiguration,
        val connection: com.gromozeka.domain.model.ai.AiConnection,
    ) {
        fun observation(
            status: AiSubscriptionQuotaObservation.Status,
            snapshot: AiSubscriptionQuotaSnapshot? = null,
        ) = AiSubscriptionQuotaObservation(
            modelConfigurationId = configuration.id,
            connectionId = connection.id,
            connectionDisplayName = connection.displayName,
            connectionKind = connection.kind,
            providerModelId = configuration.providerModelId,
            status = status,
            snapshot = snapshot,
        )
    }
}
