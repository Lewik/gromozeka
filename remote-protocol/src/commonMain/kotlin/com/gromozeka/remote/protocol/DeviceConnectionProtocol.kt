package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.domain.model.WorkerResource
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class DeviceConnectionStartRequest(
    val deviceLabel: String,
    val platform: String,
    val components: Set<DeviceConnection.Component>,
    val clientLabel: String? = null,
    val worker: DeviceConnectionWorkerRequest? = null,
)

@Serializable
data class DeviceConnectionWorkerRequest(
    val workerId: String,
    val kind: WorkerResource.Kind = WorkerResource.Kind.EXECUTION,
)

@Serializable
data class DeviceConnectionChallenge(
    val deviceToken: String,
    val userCode: String,
    val verificationPath: String,
    val verificationPathComplete: String,
    val expiresAt: Instant,
    val pollIntervalSeconds: Int,
)

@Serializable
data class DeviceConnectionCodeRequest(
    val userCode: String,
)

@Serializable
data class DeviceConnectionPasswordRequest(
    val deviceToken: String,
    val username: String,
    val password: String,
)

@Serializable
data class DeviceConnectionConsumeRequest(
    val deviceToken: String,
)

@Serializable
data class DeviceConnectionPreview(
    val deviceLabel: String,
    val platform: String,
    val components: Set<DeviceConnection.Component>,
    val workerId: String? = null,
    val workerKind: WorkerResource.Kind? = null,
    val userCode: String,
    val expiresAt: Instant,
)

@Serializable
data class DeviceConnectionConsumeResponse(
    val status: Status,
    val session: AuthenticationSessionResponse? = null,
    val worker: WorkerEnrollmentBootstrap? = null,
    val retryAfterSeconds: Int? = null,
    val message: String? = null,
) {
    @Serializable
    enum class Status {
        PENDING,
        CONNECTED,
        DENIED,
        EXPIRED,
    }
}
