package com.gromozeka.domain.model

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

data class DeviceConnection(
    val id: Id,
    val secretHash: String,
    val userCode: String,
    val deviceLabel: String,
    val platform: String,
    val components: Set<Component>,
    val clientLabel: String?,
    val worker: WorkerRequest?,
    val status: Status,
    val authorizedUserId: User.Id?,
    val decidedByUserId: User.Id?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val decidedAt: Instant?,
    val consumedAt: Instant?,
) {
    init {
        require(secretHash.length == 64) { "Device connection secret hash must contain 64 characters" }
        require(userCode.isNotBlank()) { "Device connection user code must not be blank" }
        require(deviceLabel.isNotBlank()) { "Device label must not be blank" }
        require(platform.isNotBlank()) { "Device platform must not be blank" }
        require(components.isNotEmpty()) { "Device connection must request at least one component" }
        require((Component.CLIENT in components) == (clientLabel != null)) {
            "Client label must be present exactly when Client access is requested"
        }
        require((Component.WORKER in components) == (worker != null)) {
            "Worker details must be present exactly when Worker access is requested"
        }
        require(expiresAt > createdAt) { "Device connection must expire after creation" }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "Device connection id must not be blank" }
        }
    }

    @Serializable
    enum class Component {
        CLIENT,
        WORKER,
    }

    enum class Status {
        PENDING,
        APPROVED,
        DENIED,
        CONSUMED,
        EXPIRED,
    }

    data class WorkerRequest(
        val workerId: ConversationRuntimeWorkerId,
        val bindToUser: Boolean = false,
    )
}

data class DeviceConnectionSessionCredential(
    val id: UserSession.Id,
    val tokenHash: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class DeviceConnectionDecision(
    val connection: DeviceConnection,
    val changed: Boolean,
)

data class DeviceConnectionConsumption(
    val connection: DeviceConnection,
    val session: UserSession?,
    val worker: WorkerResource?,
    val newlyConsumed: Boolean,
)
