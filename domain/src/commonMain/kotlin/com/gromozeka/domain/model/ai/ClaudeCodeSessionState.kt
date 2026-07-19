package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ClaudeCodeSessionState(
    val key: Key,
    val claudeSessionId: String,
    val coveredMessageIds: List<Conversation.Message.Id>,
    val coveredGeneratedAssistantSignatures: List<String>,
    val coveredTranscriptFingerprint: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastUsedAt: Instant,
) {
    init {
        require(claudeSessionId.isNotBlank()) { "Claude Code session id must not be blank" }
        require(coveredTranscriptFingerprint.isNotBlank()) { "Claude Code covered transcript fingerprint must not be blank" }
        require(coveredGeneratedAssistantSignatures.none { it.isBlank() }) {
            "Claude Code covered assistant signatures must not contain blank entries"
        }
    }

    @Serializable
    data class Key(
        val conversationId: Conversation.Id,
        val threadId: Conversation.Thread.Id,
        val projectId: Project.Id,
        val workspaceRootPathSnapshot: String,
        val workspaceRootPathFingerprint: String,
        val connectionId: AiConnection.Id,
        val modelConfigurationId: AiModelConfiguration.Id,
        val modelName: String,
    ) {
        init {
            require(workspaceRootPathSnapshot.isNotBlank()) {
                "Claude Code workspace root path snapshot must not be blank"
            }
            require(workspaceRootPathFingerprint.isNotBlank()) {
                "Claude Code workspace root path fingerprint must not be blank"
            }
            require(modelName.isNotBlank()) { "Claude Code model name must not be blank" }
        }
    }
}
