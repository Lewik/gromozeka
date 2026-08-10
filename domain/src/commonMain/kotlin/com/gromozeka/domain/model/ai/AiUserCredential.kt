package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.User
import kotlin.time.Instant
import kotlinx.serialization.Serializable

data class AiUserCredential(
    val userId: User.Id,
    val connectionId: AiConnection.Id,
    val secret: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String =
        "AiUserCredential(userId=$userId, connectionId=$connectionId, secret=[REDACTED], " +
            "createdAt=$createdAt, updatedAt=$updatedAt)"
}

@Serializable
data class AiUserCredentialStatus(
    val connectionId: AiConnection.Id,
    val configured: Boolean,
    val updatedAt: Instant? = null,
)
