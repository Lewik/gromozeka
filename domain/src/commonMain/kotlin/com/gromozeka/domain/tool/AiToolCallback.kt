package com.gromozeka.domain.tool

import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.serialization.Serializable

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

fun List<AiToolCallback>.supportedBy(
    capabilities: Set<ConversationRuntimeWorkerCapability>,
): List<AiToolCallback> =
    filter { capabilities.containsAll(it.metadata.requiredRuntimeCapabilities) }

@Serializable
data class AiToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: String,
    val source: String = "gromozeka",
)

@Serializable
data class AiToolMetadata(
    val returnDirect: Boolean = false,
    val requiredRuntimeCapabilities: Set<ConversationRuntimeWorkerCapability> = emptySet(),
    val executionScope: AiToolExecutionScope = AiToolExecutionScope.WORKER,
)

@Serializable
enum class AiToolExecutionScope {
    WORKER,
    WORKSPACE,
    COMMAND_TASK_OWNER,
}

@Serializable
data class AiToolDescriptor(
    val definition: AiToolDefinition,
    val metadata: AiToolMetadata,
)

@Serializable
data class AiToolExecutionTarget(
    val workerId: ConversationRuntimeWorkerId,
    val workspaceId: Workspace.Id? = null,
)
