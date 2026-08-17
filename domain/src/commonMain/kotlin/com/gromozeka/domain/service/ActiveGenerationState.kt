package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class ActiveGenerationSnapshot(
    val generationId: String,
    val conversationId: Conversation.Id,
    val taskId: ConversationRuntimeTask.Id,
    val provider: String,
    val modelName: String,
    val iteration: Int,
    val phase: Phase,
    val startedAt: Instant,
    val updatedAt: Instant,
    val inputMessageCount: Int,
    val inputContentItemCount: Int,
    val systemPromptCount: Int,
    val availableToolCount: Int,
) {
    init {
        require(generationId.isNotBlank()) { "Active generation id must not be blank" }
        require(provider.isNotBlank()) { "Active generation provider must not be blank" }
        require(modelName.isNotBlank()) { "Active generation model name must not be blank" }
        require(iteration > 0) { "Active generation iteration must be positive" }
        require(updatedAt >= startedAt) { "Active generation update cannot precede its start" }
        require(inputMessageCount >= 0) { "Active generation input message count must not be negative" }
        require(inputContentItemCount >= 0) { "Active generation content item count must not be negative" }
        require(systemPromptCount >= 0) { "Active generation system prompt count must not be negative" }
        require(availableToolCount >= 0) { "Active generation tool count must not be negative" }
    }

    @Serializable
    enum class Phase {
        WAITING_FOR_MODEL,
    }
}

interface ActiveGenerationPublisher {
    suspend fun publish(snapshot: ActiveGenerationSnapshot)

    suspend fun clear(
        conversationId: Conversation.Id,
        generationId: String,
    )
}
