package com.gromozeka.server

import com.gromozeka.application.service.AsyncToolScopeAccess
import com.gromozeka.domain.model.AsyncToolOperation
import com.gromozeka.domain.model.AsyncToolOutcome
import com.gromozeka.domain.repository.AsyncToolOperationRepository
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import kotlin.time.Clock

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.server.enabled"], havingValue = "true", matchIfMissing = true)
class AsyncToolOperationToolContributor(
    private val operations: AsyncToolOperationRepository,
    private val scopes: AsyncToolScopeAccess,
) : AiToolCallbackContributor {
    override val callbacks: List<AiToolCallback> = listOf(callback(false), callback(true))

    private fun callback(cancel: Boolean): AiToolCallback = object : AiToolCallback {
        override val metadata = PreloadedServerToolMetadata.withContextRequirements(AiToolContextField.USER,
            optional = setOf(AiToolContextField.PROJECT))
        override val definition = AiToolDefinition(
            name = if (cancel) "grz_async_tool_cancel" else "grz_async_tool_get",
            description = if (cancel)
                "Request cancellation of your durable async tool operation. Cancellation before execution avoids effects; after execution starts it is best-effort and does not imply rollback. Do not repeat an action with an unknown outcome."
            else "Read your durable async operation status and saved outcome by operation_id. ACCEPTED is a receipt, not execution success. Results retain original source bindings; do not resubmit the action to retrieve its result.",
            inputSchema = """{"type":"object","properties":{"operation_id":{"type":"string","minLength":1,"maxLength":128}},"required":["operation_id"],"additionalProperties":false}""",
            annotations = AiToolAnnotations(readOnlyHint = !cancel, destructiveHint = false, idempotentHint = true),
        )
        override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
            val input = Json.parseToJsonElement(toolInput).jsonObject
            require(input.keys == setOf("operation_id")) { "Unexpected async operation arguments" }
            val value = input.getValue("operation_id") as? JsonPrimitive
            require(value != null && value.isString && value.content.isNotBlank()) { "operation_id is required" }
            val operation = operations.find(AsyncToolOperation.Id(value.content)) ?: error("Async operation is unavailable; do not resubmit the action")
            scopes.requireInspection(context.requiredUserId(), operation, cancel)
            context?.getString(TOOL_CONTEXT_PROJECT_ID)?.let { require(it == operation.invocation.origin.projectId.value) { "Async operation belongs to another project" } }
            context?.cancellationSignal?.throwIfCancellationRequested()
            val requested = if (cancel) operations.requestCancellation(operation.id, Clock.System.now()) else false
            val current = checkNotNull(operations.find(operation.id))
            buildJsonObject {
                put("operation_id", current.id.value)
                put("status", current.phase.name)
                put("cancel_requested", current.cancelRequestedAt != null)
                if (cancel) put("cancellation_request_accepted", requested)
                put("conversation_id", current.invocation.origin.conversationId.value)
                put("source_thread_id", current.invocation.origin.threadId.value)
                put("agent_id", current.invocation.origin.agentDefinitionId.value)
                put("tool_name", current.invocation.modelToolName)
                put("origin_readonly", current.invocation.origin.readOnly)
                put("created_at", current.createdAt.toString())
                current.startedAt?.let { put("started_at", it.toString()) }
                current.completedAt?.let { put("completed_at", it.toString()) }
                put("notification_delivered", current.projectedAt != null)
                current.outcome?.let { put("outcome", Json.encodeToJsonElement(AsyncToolOutcome.serializer(), it)) }
                if (current.phase == AsyncToolOperation.Phase.OUTCOME_UNKNOWN)
                    put("message", "Effects may have occurred. Inspect the existing state; no automatic effect replay is permitted.")
            }.toString()
        }
    }
}
