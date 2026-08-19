package com.gromozeka.domain.model.ai

import kotlin.time.Instant
import kotlinx.serialization.Serializable

interface AiSubscriptionConnection {
    val quotaPacing: AiSubscriptionQuotaPacingPolicy
}

@Serializable
data class AiSubscriptionQuotaPacingPolicy(
    val enabled: Boolean = true,
    val reservePercent: Double = DEFAULT_RESERVE_PERCENT,
    val minimumHeadroomPercent: Double = DEFAULT_MINIMUM_HEADROOM_PERCENT,
    val refreshIntervalSeconds: Long = DEFAULT_REFRESH_INTERVAL_SECONDS,
) {
    init {
        require(reservePercent in 0.0..100.0) { "Subscription quota reserve must be between 0 and 100 percent" }
        require(minimumHeadroomPercent in 0.0..100.0) {
            "Subscription quota minimum headroom must be between 0 and 100 percent"
        }
        require(reservePercent + minimumHeadroomPercent <= 100.0) {
            "Subscription quota reserve and minimum headroom cannot exceed 100 percent"
        }
        require(refreshIntervalSeconds > 0) { "Subscription quota refresh interval must be positive" }
    }

    companion object {
        const val DEFAULT_RESERVE_PERCENT = 15.0
        const val DEFAULT_MINIMUM_HEADROOM_PERCENT = 2.0
        const val DEFAULT_REFRESH_INTERVAL_SECONDS = 60L
    }
}

@Serializable
data class AiSubscriptionQuotaRequest(
    val connection: AiConnection,
    val modelId: String? = null,
    val userId: String? = null,
) {
    init {
        require(connection is AiSubscriptionConnection) {
            "Subscription quota requires a subscription-backed AI connection"
        }
        require(modelId == null || modelId.isNotBlank()) { "Subscription quota model id must not be blank" }
        require(userId == null || userId.isNotBlank()) { "Subscription quota user id must not be blank" }
    }
}

@Serializable
data class AiSubscriptionQuotaSnapshot(
    val connectionId: AiConnection.Id,
    val observedAt: Instant,
    val windows: List<AiSubscriptionQuotaWindow>,
    val unlimited: Boolean = false,
    val usageBlocked: Boolean = false,
) {
    init {
        require(unlimited || usageBlocked || windows.isNotEmpty()) {
            "Subscription quota snapshot must contain at least one finite window, be unlimited, or report blocked usage"
        }
        require(windows.map { it.id }.distinct().size == windows.size) {
            "Subscription quota window ids must be unique"
        }
    }
}

@Serializable
data class AiSubscriptionQuotaObservation(
    val modelConfigurationId: AiModelConfiguration.Id,
    val connectionId: AiConnection.Id,
    val connectionDisplayName: String,
    val connectionKind: AiConnection.Kind,
    val providerModelId: String,
    val status: Status,
    val snapshot: AiSubscriptionQuotaSnapshot? = null,
) {
    init {
        require(connectionDisplayName.isNotBlank()) { "Subscription quota connection name must not be blank" }
        require(providerModelId.isNotBlank()) { "Subscription quota provider model id must not be blank" }
        require((status == Status.FRESH || status == Status.STALE) == (snapshot != null)) {
            "Fresh and stale subscription quota observations must contain a snapshot"
        }
        require(snapshot == null || snapshot.connectionId == connectionId) {
            "Subscription quota snapshot must belong to the observed connection"
        }
    }

    @Serializable
    enum class Status {
        FRESH,
        STALE,
        UNAVAILABLE,
        NOT_SUPPORTED,
    }
}

@Serializable
data class AiSubscriptionQuotaWindow(
    val id: String,
    val displayName: String,
    val usedPercent: Double,
    val startedAt: Instant,
    val resetsAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "Subscription quota window id must not be blank" }
        require(displayName.isNotBlank()) { "Subscription quota window display name must not be blank" }
        require(usedPercent in 0.0..100.0) { "Subscription quota usage must be between 0 and 100 percent" }
        require(resetsAt > startedAt) { "Subscription quota reset must be after its start" }
    }
}
