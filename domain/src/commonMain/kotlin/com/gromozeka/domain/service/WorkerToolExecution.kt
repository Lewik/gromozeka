package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.ToolExecutionContext

data class WorkerToolExecutionResult(
    val results: List<Conversation.Message.ContentItem.ToolResult>,
    val returnDirect: Boolean,
)

interface WorkerToolExecutionClient {
    suspend fun execute(
        target: ConversationRuntimeWorkerIdentity,
        executionTarget: ConversationRuntimeTaskTarget.Worker,
        toolCalls: List<Conversation.Message.ContentItem.ToolCall>,
        toolContext: ToolExecutionContext,
        resolvedSecretsByToolCallId: Map<String, Map<String, String>> = emptyMap(),
    ): WorkerToolExecutionResult
}

interface WorkerToolCatalogPublisher {
    val capabilities: Set<ConversationRuntimeCapability>

    suspend fun updateAdvertisedTools(tools: List<AiToolDescriptor>)
}
