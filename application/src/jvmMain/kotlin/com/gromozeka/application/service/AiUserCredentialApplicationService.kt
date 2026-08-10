package com.gromozeka.application.service

import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiUserCredentialStatus
import com.gromozeka.domain.repository.AiUserCredentialRepository
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.SecurityAuditRecorder
import kotlin.time.Clock
import org.springframework.stereotype.Service

@Service
class AiUserCredentialApplicationService(
    private val repository: AiUserCredentialRepository,
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val securityAuditRecorder: SecurityAuditRecorder,
) {
    suspend fun status(
        userId: User.Id,
        connectionId: AiConnection.Id,
    ): AiUserCredentialStatus {
        requireCopilotConnection(connectionId)
        val credential = repository.find(userId, connectionId)
        return AiUserCredentialStatus(
            connectionId = connectionId,
            configured = credential != null,
            updatedAt = credential?.updatedAt,
        )
    }

    suspend fun configure(
        userId: User.Id,
        connectionId: AiConnection.Id,
        secret: String,
    ): AiUserCredentialStatus {
        val connection = requireCopilotConnection(connectionId)
        require(connection.authMode == AiConnection.GitHubCopilotAuthMode.PER_USER_TOKEN) {
            "GitHub Copilot connection ${connectionId.value} does not use per-user tokens"
        }
        val normalized = secret.trim()
        require(normalized.length <= MAX_TOKEN_LENGTH) { "GitHub token is too long" }
        require(SUPPORTED_TOKEN_PREFIXES.any(normalized::startsWith)) {
            "GitHub Copilot requires a gho_, ghu_, or github_pat_ user token"
        }
        val saved = repository.save(userId, connectionId, normalized, Clock.System.now())
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = userId,
                action = SecurityAuditEvent.Action.AI_USER_CREDENTIAL_CONFIGURED,
                targetType = SecurityAuditEvent.TargetType.AI_CONNECTION,
                targetId = connectionId.value,
            )
        )
        return AiUserCredentialStatus(connectionId, configured = true, updatedAt = saved.updatedAt)
    }

    suspend fun remove(
        userId: User.Id,
        connectionId: AiConnection.Id,
    ): AiUserCredentialStatus {
        requireCopilotConnection(connectionId)
        if (repository.delete(userId, connectionId)) {
            securityAuditRecorder.record(
                SecurityAuditRecord(
                    actorUserId = userId,
                    action = SecurityAuditEvent.Action.AI_USER_CREDENTIAL_REMOVED,
                    targetType = SecurityAuditEvent.TargetType.AI_CONNECTION,
                    targetId = connectionId.value,
                )
            )
        }
        return AiUserCredentialStatus(connectionId, configured = false)
    }

    private fun requireCopilotConnection(connectionId: AiConnection.Id): AiConnection.GitHubCopilot =
        aiConfigurationProvider.catalog.connections
            .firstOrNull { it.id == connectionId }
            ?.let { connection ->
                connection as? AiConnection.GitHubCopilot
                    ?: error("AI connection ${connectionId.value} is not GitHub Copilot")
            }
            ?: error("AI connection not found: ${connectionId.value}")

    companion object {
        private const val MAX_TOKEN_LENGTH = 8_192
        private val SUPPORTED_TOKEN_PREFIXES = listOf("gho_", "ghu_", "github_pat_")
    }
}
