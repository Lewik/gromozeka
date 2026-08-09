package com.gromozeka.domain.service

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiUserCredentialStatus

interface CurrentUserAiCredentialService {
    suspend fun status(connectionId: AiConnection.Id): AiUserCredentialStatus

    suspend fun configure(
        connectionId: AiConnection.Id,
        secret: String,
    ): AiUserCredentialStatus

    suspend fun remove(connectionId: AiConnection.Id): AiUserCredentialStatus
}
