package com.gromozeka.domain.repository

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiUserCredential
import kotlin.time.Instant

interface AiUserCredentialRepository {
    suspend fun find(
        userId: User.Id,
        connectionId: AiConnection.Id,
    ): AiUserCredential?

    suspend fun save(
        userId: User.Id,
        connectionId: AiConnection.Id,
        secret: String,
        updatedAt: Instant,
    ): AiUserCredential

    suspend fun delete(
        userId: User.Id,
        connectionId: AiConnection.Id,
    ): Boolean
}
