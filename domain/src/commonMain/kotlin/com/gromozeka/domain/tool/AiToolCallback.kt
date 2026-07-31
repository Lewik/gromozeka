package com.gromozeka.domain.tool

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.model.WorkspaceMount
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

    val available: Boolean
        get() = true

    fun call(toolInput: String, context: ToolExecutionContext? = null): String
}

interface AiToolCallbackContributor {
    val callbacks: List<AiToolCallback>
}

fun List<AiToolCallback>.supportedBy(
    capabilities: Set<ConversationRuntimeCapability>,
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
    val requiredRuntimeCapabilities: Set<ConversationRuntimeCapability> = emptySet(),
    val executionScope: AiToolExecutionScope = AiToolExecutionScope.WORKER,
    val visibleToMemoryPipeline: Boolean = true,
)

@Serializable
enum class AiToolExecutionScope {
    CONVERSATION_RUNTIME,
    WORKER,
    WORKSPACE,
    COMMAND_TASK_OWNER,
    COMMAND_MONITOR_OWNER,
}

@Serializable
data class AiToolDescriptor(
    val definition: AiToolDefinition,
    val metadata: AiToolMetadata,
)

@Serializable
data class AiToolExecutionTarget(
    val workerId: ConversationRuntimeWorkerId? = null,
    val workspaceMountId: WorkspaceMount.Id? = null,
) {
    init {
        require((workerId == null) != (workspaceMountId == null)) {
            "AI tool execution target must select exactly one worker or workspace mount"
        }
    }
}
