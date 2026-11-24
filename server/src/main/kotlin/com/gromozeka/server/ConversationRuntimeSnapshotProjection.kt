package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import kotlinx.serialization.json.JsonNull

internal fun ConversationRuntimeSnapshot.projectForRemoteClient(): ConversationRuntimeSnapshot = copy(
    activeTask = activeTask?.projectForRemoteClient(),
    activeInsertions = activeInsertions.map(ConversationRuntimeTask::projectForRemoteClient),
    pendingTasks = pendingTasks.map(ConversationRuntimeTask::projectForRemoteClient),
)

private fun ConversationRuntimeTask.projectForRemoteClient(): ConversationRuntimeTask {
    val projectedPayload = when (val current = payload) {
        is ConversationRuntimeTask.Payload.PostMessage -> current.userMessage.projectRuntimeShell().let { message ->
            if (message === current.userMessage) current else current.copy(userMessage = message)
        }
        is ConversationRuntimeTask.Payload.AgentInvocation -> current.userMessage.projectRuntimeShell().let { message ->
            if (message === current.userMessage) current else current.copy(userMessage = message)
        }
        is ConversationRuntimeTask.Payload.AgentResponse,
        is ConversationRuntimeTask.Payload.HistoryMutation,
        is ConversationRuntimeTask.Payload.LlmCall,
        is ConversationRuntimeTask.Payload.ApplyPreparedCompaction,
        is ConversationRuntimeTask.Payload.ResponseReview,
        is ConversationRuntimeTask.Payload.ToolExecution,
        is ConversationRuntimeTask.Payload.ToolResultProcessing,
        is ConversationRuntimeTask.Payload.MemoryRecall,
        is ConversationRuntimeTask.Payload.MemoryRunCompletion,
        is ConversationRuntimeTask.Payload.BackgroundActivityCompletion,
        is ConversationRuntimeTask.Payload.ExecutionIncident,
        -> current
    }
    return if (projectedPayload === payload) this else copy(payload = projectedPayload)
}

private fun Conversation.Message.projectRuntimeShell(): Conversation.Message {
    if (role != Conversation.Message.Role.RUNTIME) return this
    return Conversation.Message(
        id = id,
        conversationId = conversationId,
        role = role,
        author = Conversation.Message.Author.Runtime(),
        content = listOf(Conversation.Message.ContentItem.RuntimeJson(JsonNull)),
        createdAt = createdAt,
    )
}
