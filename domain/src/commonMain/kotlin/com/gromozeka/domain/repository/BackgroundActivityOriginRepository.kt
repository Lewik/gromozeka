package com.gromozeka.domain.repository

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.serialization.Serializable

/** Server-owned provenance. Never accept an actor claimed by a Worker or infer it from recent chat. */
@Serializable
data class BackgroundActivityOrigin(
    val kind: Kind,
    val activityId: String,
    val conversationId: Conversation.Id,
    val agentDefinitionId: AgentDefinition.Id,
    val actorUserId: User.Id,
    val workerId: ConversationRuntimeWorkerId,
    val workspaceMountId: WorkspaceMount.Id,
    val workerRequestId: String,
    val toolCallId: String,
) {
    @Serializable
    enum class Kind { COMMAND, MONITOR }
    data class Key(val kind: Kind, val activityId: String)
    val key: Key get() = Key(kind, activityId)
}

interface BackgroundActivityOriginRepository {
    /** Idempotent only for the same complete binding; reassignment must fail. */
    suspend fun bind(origin: BackgroundActivityOrigin)
    suspend fun find(
        conversationId: Conversation.Id,
        keys: Set<BackgroundActivityOrigin.Key>,
    ): Map<BackgroundActivityOrigin.Key, BackgroundActivityOrigin>
}
