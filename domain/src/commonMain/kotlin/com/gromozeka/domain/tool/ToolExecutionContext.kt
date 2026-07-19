package com.gromozeka.domain.tool

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.service.ConversationRuntimeWorkerId

const val TOOL_CONTEXT_CONVERSATION_ID = "conversationId"
const val TOOL_CONTEXT_PROJECT_ID = "projectId"
const val TOOL_CONTEXT_WORKSPACE_ID = "workspaceId"
const val TOOL_CONTEXT_WORKSPACE_ROOT_PATH = "workspaceRootPath"
const val TOOL_CONTEXT_WORKER_ID = "workerId"

/**
 * Framework-agnostic execution context for AI tools.
 */
data class ToolExecutionContext(
    private val values: Map<String, Any?> = emptyMap(),
    val cancellationSignal: ToolCancellationSignal = ToolCancellationSignal.None,
) {
    fun get(key: String): Any? = values[key]

    fun getString(key: String): String? = values[key] as? String

    fun getContext(): Map<String, Any?> = values

    fun asMap(): Map<String, Any?> = values

    fun withCancellationSignal(signal: ToolCancellationSignal): ToolExecutionContext =
        copy(cancellationSignal = signal)
}

fun ToolExecutionContext?.requiredProjectId(): Project.Id =
    requiredString(TOOL_CONTEXT_PROJECT_ID, "Project id").let(Project::Id)

fun ToolExecutionContext?.requiredWorkspaceId(): Workspace.Id =
    requiredString(TOOL_CONTEXT_WORKSPACE_ID, "Workspace id").let(Workspace::Id)

fun ToolExecutionContext?.requiredWorkspaceRootPath(): String =
    requiredString(TOOL_CONTEXT_WORKSPACE_ROOT_PATH, "Workspace root path")

fun ToolExecutionContext?.requiredWorkerId(): ConversationRuntimeWorkerId =
    requiredString(TOOL_CONTEXT_WORKER_ID, "Worker id").let(::ConversationRuntimeWorkerId)

private fun ToolExecutionContext?.requiredString(key: String, label: String): String =
    this?.getString(key)?.takeIf { it.isNotBlank() }
        ?: error("$label is required in tool execution context")

fun interface ToolCancellationSignal {
    fun throwIfCancellationRequested()

    companion object {
        val None = ToolCancellationSignal {}
    }
}
