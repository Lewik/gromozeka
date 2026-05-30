package com.gromozeka.domain.tool

import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability

/**
 * Framework-agnostic executable tool descriptor.
 *
 * Application and domain code operate on this contract rather than on
 * provider-specific tool callback abstractions.
 */
interface AiToolCallback {
    val definition: AiToolDefinition

    val metadata: AiToolMetadata
        get() = AiToolMetadata()

    fun call(toolInput: String, context: ToolExecutionContext? = null): String
}

data class AiToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: String
)

data class AiToolMetadata(
    val returnDirect: Boolean = false,
    val requiredRuntimeCapabilities: Set<ConversationRuntimeWorkerCapability> = emptySet(),
)
