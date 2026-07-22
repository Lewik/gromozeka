package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC
import com.gromozeka.domain.tool.TOOL_CONTEXT_TOOL_NAME
import com.gromozeka.domain.tool.ToolExecutionContext

internal fun ToolExecutionContext?.memoryOperationResultDeliveryOrNull(): MemoryOperationResultDelivery? {
    if (this?.getString(TOOL_CONTEXT_MEMORY_RESULT_DELIVERY) != TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC) {
        return null
    }
    val conversationId = getString(TOOL_CONTEXT_CONVERSATION_ID)
        ?.takeIf { it.isNotBlank() }
        ?.let(Conversation::Id)
        ?: return null
    val agentDefinitionId = getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)
        ?.takeIf { it.isNotBlank() }
        ?.let(AgentDefinition::Id)
        ?: return null
    val sourceToolName = getString(TOOL_CONTEXT_TOOL_NAME)?.takeIf { it.isNotBlank() } ?: return null
    val statusToolName = sourceToolName.substringBeforeLast("__", missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?.let { prefix -> "${prefix}__$MEMORY_RUN_STATUS_TOOL_NAME" }
        ?: MEMORY_RUN_STATUS_TOOL_NAME
    return MemoryOperationResultDelivery(
        conversationId = conversationId,
        agentDefinitionId = agentDefinitionId,
        statusToolName = statusToolName,
    )
}
