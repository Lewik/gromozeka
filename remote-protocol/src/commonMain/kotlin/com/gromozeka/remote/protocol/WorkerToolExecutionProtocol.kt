package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import kotlinx.serialization.Serializable

@Serializable
data class WorkerToolExecutionRequest(
    val executionTarget: ConversationRuntimeTaskTarget.Worker,
    val toolCalls: List<Conversation.Message.ContentItem.ToolCall>,
    val toolContext: Map<String, String>,
)

@Serializable
data class WorkerToolExecutionResponse(
    val results: List<Conversation.Message.ContentItem.ToolResult>,
    val returnDirect: Boolean,
)
