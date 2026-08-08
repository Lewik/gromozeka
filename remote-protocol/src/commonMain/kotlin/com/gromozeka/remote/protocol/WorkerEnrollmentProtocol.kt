package com.gromozeka.remote.protocol

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.model.WorkerResource
import kotlinx.serialization.Serializable

@Serializable
data class WorkerEnrollmentAvailability(
    val available: Boolean,
    val unavailableReason: String? = null,
)

@Serializable
data class WorkerEnrollmentToken(
    val token: String,
    val expiresAt: String,
)

@Serializable
data class WorkerEnrollmentConsumeRequest(
    val token: String,
    val workerId: String,
    val kind: WorkerResource.Kind = WorkerResource.Kind.EXECUTION,
)

@Serializable
data class WorkerEnrollmentBootstrap(
    val workerId: String,
    val gatewayCredential: String,
    val capabilities: Set<ConversationRuntimeCapability>,
    val kind: WorkerResource.Kind = WorkerResource.Kind.EXECUTION,
)
