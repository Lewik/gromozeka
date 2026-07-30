package com.gromozeka.remote.protocol

import com.gromozeka.domain.service.ConversationRuntimeCapability
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
)

@Serializable
data class WorkerEnrollmentBootstrap(
    val workerId: String,
    val gatewayCredential: String,
    val postgresJdbcUrl: String,
    val postgresUsername: String,
    val postgresPassword: String,
    val rabbitmqAddresses: String,
    val rabbitmqUsername: String,
    val rabbitmqPassword: String,
    val capabilities: Set<ConversationRuntimeCapability>,
)
