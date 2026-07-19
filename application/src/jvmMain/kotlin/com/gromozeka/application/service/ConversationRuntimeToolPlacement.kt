package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolRuntimeAffinityTarget

internal fun conversationRuntimeToolExecutionRequirements(
    toolCalls: List<ContentItem.ToolCall>,
    availableTools: List<AiToolCallback>,
    localAgentAffinity: ConversationRuntimeWorkerAffinity? = null,
    commandTaskOwnerAffinities: Map<ContentItem.ToolCall.Id, ConversationRuntimeWorkerAffinity> = emptyMap(),
): ConversationRuntimeTaskRequirements {
    val toolsByName = availableTools.associateBy { it.definition.name }
    val ownerAffinities = mutableSetOf<ConversationRuntimeWorkerAffinity>()
    var requiresProjectAffinity = false
    val capabilities = buildSet {
        add(ConversationRuntimeWorkerCapability.TOOL_EXECUTION)
        toolCalls.forEach { toolCall ->
            val tool = toolsByName[toolCall.call.name]
                ?: error("Tool execution requested unknown tool: ${toolCall.call.name}")
            addAll(tool.metadata.requiredRuntimeCapabilities)
            when (tool.metadata.runtimeAffinityTarget) {
                AiToolRuntimeAffinityTarget.PROJECT -> requiresProjectAffinity = true
                AiToolRuntimeAffinityTarget.COMMAND_TASK_OWNER -> {
                    val ownerAffinity = commandTaskOwnerAffinities[toolCall.id]
                    if (ownerAffinity == null) {
                        requiresProjectAffinity = true
                    } else {
                        ownerAffinities += ownerAffinity
                    }
                }
                null -> Unit
            }
        }
    }
    require(ownerAffinities.size <= 1) {
        "A single tool execution task cannot target command tasks owned by different workers"
    }
    if (ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL in capabilities) {
        require(ownerAffinities.isNotEmpty() || requiresProjectAffinity) {
            "Local agent tool execution must declare a runtime affinity target"
        }
    }

    return ConversationRuntimeTaskRequirements(
        capabilities = capabilities,
        affinity = ownerAffinities.singleOrNull()
            ?: localAgentAffinity.takeIf { requiresProjectAffinity }
            ?: run {
                require(ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL !in capabilities) {
                    "Project affinity is required for local agent tool execution"
                }
                null
            },
    )
}
