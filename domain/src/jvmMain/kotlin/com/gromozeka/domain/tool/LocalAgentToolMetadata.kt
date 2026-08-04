package com.gromozeka.domain.tool

import com.gromozeka.domain.service.ConversationRuntimeCapability

val LocalAgentToolMetadata = AiToolMetadata(
    requiredRuntimeCapabilities = setOf(ConversationRuntimeCapability.LOCAL_AGENT_TOOL),
    executionScope = AiToolExecutionScope.WORKSPACE,
)

val CommandTaskOwnerToolMetadata = AiToolMetadata(
    requiredRuntimeCapabilities = setOf(ConversationRuntimeCapability.LOCAL_AGENT_TOOL),
    executionScope = AiToolExecutionScope.COMMAND_TASK_OWNER,
)

val CommandMonitorOwnerToolMetadata = AiToolMetadata(
    requiredRuntimeCapabilities = setOf(ConversationRuntimeCapability.LOCAL_AGENT_TOOL),
    executionScope = AiToolExecutionScope.COMMAND_MONITOR_OWNER,
)

val ServerToolMetadata = AiToolMetadata(
    executionScope = AiToolExecutionScope.SERVER,
)

val WorkerManagementToolMetadata = AiToolMetadata(
    requiredRuntimeCapabilities = setOf(ConversationRuntimeCapability.LOCAL_AGENT_TOOL),
    executionScope = AiToolExecutionScope.WORKER,
)

val WorkerInspectionToolMetadata = AiToolMetadata(
    requiredRuntimeCapabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
    executionScope = AiToolExecutionScope.WORKER,
)

val WorkerComputerUseToolMetadata = AiToolMetadata(
    requiredRuntimeCapabilities = setOf(
        ConversationRuntimeCapability.TOOL_EXECUTION,
        ConversationRuntimeCapability.COMPUTER_USE,
    ),
    executionScope = AiToolExecutionScope.WORKER,
    logInput = false,
)
