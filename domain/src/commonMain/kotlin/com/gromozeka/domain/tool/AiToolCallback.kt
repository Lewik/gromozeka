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

    val available: Boolean
        get() = true

    fun call(toolInput: String, context: ToolExecutionContext? = null): String

    fun callResult(toolInput: String, context: ToolExecutionContext? = null): List<AiToolResult> =
        listOf(AiToolResult.Text(call(toolInput, context)))
}

sealed interface AiToolResult {
    data class Text(val content: String) : AiToolResult

    data class Binary(
        val content: ByteArray,
        val fileName: String,
        val mediaType: String,
    ) : AiToolResult {
        init {
            require(content.isNotEmpty()) { "Binary tool result must not be empty" }
            require(fileName.isNotBlank()) { "Binary tool result file name must not be blank" }
            require(mediaType.isNotBlank()) { "Binary tool result media type must not be blank" }
        }

        override fun equals(other: Any?): Boolean =
            other is Binary &&
                content.contentEquals(other.content) &&
                fileName == other.fileName &&
                mediaType == other.mediaType

        override fun hashCode(): Int {
            var result = content.contentHashCode()
            result = 31 * result + fileName.hashCode()
            result = 31 * result + mediaType.hashCode()
            return result
        }
    }
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
    val executionScope: AiToolExecutionScope,
    val loadingPolicy: AiToolLoadingPolicy = AiToolLoadingPolicy.ON_DEMAND,
    val visibleToMemoryPipeline: Boolean = true,
    val logInput: Boolean = true,
)

@Serializable
enum class AiToolLoadingPolicy {
    ON_DEMAND,
    PRELOAD_WHEN_AVAILABLE,
    PRELOAD_WHEN_MEMORY_ENABLED,
}

@Serializable
enum class AiToolExecutionScope {
    SERVER,
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
    val requestPolicy: com.gromozeka.domain.service.WorkerRequestPolicy? = null,
) {
    init {
        require((workerId == null) != (workspaceMountId == null)) {
            "AI tool execution target must select exactly one worker or workspace mount"
        }
    }
}
