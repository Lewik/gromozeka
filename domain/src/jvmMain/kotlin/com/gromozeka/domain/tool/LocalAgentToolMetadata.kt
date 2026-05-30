package com.gromozeka.domain.tool

import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability

val LocalAgentToolMetadata = AiToolMetadata(
    requiredRuntimeCapabilities = setOf(ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL),
)
