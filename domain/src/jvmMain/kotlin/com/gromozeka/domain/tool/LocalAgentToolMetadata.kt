package com.gromozeka.domain.tool

import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability

val LocalAgentToolMetadata = AiToolMetadata(
    requiredRuntimeCapabilities = setOf(ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL),
    executionScope = AiToolExecutionScope.WORKSPACE,
)

val CommandTaskOwnerToolMetadata = AiToolMetadata(
    requiredRuntimeCapabilities = setOf(ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL),
    executionScope = AiToolExecutionScope.COMMAND_TASK_OWNER,
)

val WorkerManagementToolMetadata = AiToolMetadata(
    requiredRuntimeCapabilities = setOf(ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL),
    executionScope = AiToolExecutionScope.WORKER,
)
