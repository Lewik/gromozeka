package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.tool.AiToolCallback

internal fun conversationRuntimeToolExecutionRequirements(
    toolCalls: List<ContentItem.ToolCall>,
    availableTools: List<AiToolCallback>,
    localAgentAffinity: ConversationRuntimeWorkerAffinity? = null,
): ConversationRuntimeTaskRequirements {
    val toolsByName = availableTools.associateBy { it.definition.name }
    val capabilities = buildSet {
        add(ConversationRuntimeWorkerCapability.TOOL_EXECUTION)
        toolCalls.forEach { toolCall ->
            val tool = toolsByName[toolCall.call.name]
                ?: error("Tool execution requested unknown tool: ${toolCall.call.name}")
            addAll(tool.metadata.requiredRuntimeCapabilities)
        }
    }

    return ConversationRuntimeTaskRequirements(
        capabilities = capabilities,
        affinity = localAgentAffinity.takeIf {
            ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL in capabilities
        },
    )
}
