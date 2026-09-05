package com.gromozeka.remote.protocol

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.model.User
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
    val platform: String? = null,
    val bindToUser: Boolean = false,
)

@Serializable
data class WorkerEnrollmentBootstrap(
    val workerId: String,
    val gatewayCredential: String,
    val capabilities: Set<ConversationRuntimeCapability>,
    val subjectUserId: User.Id? = null,
)
