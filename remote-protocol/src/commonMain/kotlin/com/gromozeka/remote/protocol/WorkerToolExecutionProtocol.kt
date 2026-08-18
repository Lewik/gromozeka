package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import kotlinx.serialization.Serializable

@Serializable
data class WorkerToolExecutionRequest(
    val executionTarget: ConversationRuntimeTaskTarget.Worker,
    val toolCalls: List<Conversation.Message.ContentItem.ToolCall>,
    val toolContext: Map<String, String>,
    val resolvedSecretsByToolCallId: Map<String, Map<String, String>> = emptyMap(),
) {
    override fun toString(): String =
        "WorkerToolExecutionRequest(executionTarget=$executionTarget, toolCalls=$toolCalls, " +
            "toolContext=$toolContext, resolvedSecretsByToolCallId=[REDACTED])"
}

@Serializable
data class WorkerToolExecutionResponse(
    val results: List<Conversation.Message.ContentItem.ToolResult>,
    val returnDirect: Boolean,
)
