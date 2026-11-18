package com.gromozeka.application.service

import com.gromozeka.domain.model.AsyncToolOperation
import com.gromozeka.domain.model.AsyncToolOperation.Phase
import com.gromozeka.domain.model.AsyncToolOutcome
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.AsyncToolOperationRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.Instant

/** Durable result producer shared by local callbacks and the existing Worker response observer. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = ["gromozeka.runtime.server.enabled"], havingValue = "true", matchIfMissing = true)
class AsyncToolOutcomeService(
    private val operations: AsyncToolOperationRepository,
    private val scopes: AsyncToolScopeAccess,
) {
    private val changed = Channel<Conversation.Id>(Channel.UNLIMITED)
    val changes = changed.receiveAsFlow()

    suspend fun finish(
        observed: AsyncToolOperation,
        phase: Phase,
        reported: AsyncToolOutcome,
        at: Instant = Clock.System.now(),
    ): Boolean {
        if (observed.phase.isTerminal) return false
        val origin = observed.invocation.origin
        val won = operations.finish(observed, phase, reported, at)
        if (won) check(changed.trySend(origin.conversationId).isSuccess) { "Async completion event stream is closed" }
        return won
    }

    suspend fun canDeliver(operation: AsyncToolOperation): Boolean = scopes.deliveryConversation(operation.invocation.origin) != null

    fun notification(operation: AsyncToolOperation): Conversation.Message {
        require(operation.phase.isTerminal && operation.acknowledgedAt != null) { "Async notification requires its durable result and source ACK" }
        return notification(operation, operation.phase, checkNotNull(operation.outcome), checkNotNull(operation.completedAt))
    }

    private fun notification(operation: AsyncToolOperation, phase: Phase, outcome: AsyncToolOutcome, at: Instant): Conversation.Message {
        val origin = operation.invocation.origin
        val structured = outcome.structuredContent?.toString()
        val outputs = outcome.content + if (structured != null && outcome.content.none {
                it is ContentItem.ToolResult.Data.Text && it.content == structured
            }) listOf(ContentItem.ToolResult.Data.Text("[Structured tool result]\n$structured")) else emptyList()
        val summary = when (phase) {
            Phase.SUCCEEDED -> "Async tool invocation completed: ${operation.invocation.modelToolName}"
            Phase.FAILED -> "Async tool invocation failed: ${operation.invocation.modelToolName}"
            Phase.CANCELLED -> "Async tool invocation was cancelled before execution: ${operation.invocation.modelToolName}"
            Phase.OUTCOME_UNKNOWN -> "Async tool invocation has an unknown outcome: ${operation.invocation.modelToolName}. Effects may have occurred. Do not repeat it automatically."
            else -> error("An unfinished invocation has no completion notification")
        }
        val level = when (phase) {
            Phase.SUCCEEDED -> ContentItem.RuntimeNotification.Level.INFO
            Phase.CANCELLED -> ContentItem.RuntimeNotification.Level.WARNING
            else -> ContentItem.RuntimeNotification.Level.ERROR
        }
        return Conversation.Message(
            id = Conversation.Message.Id("async-tool:${operation.id.value}:notification"),
            conversationId = origin.conversationId,
            role = Conversation.Message.Role.RUNTIME_NOTIFICATION,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(ContentItem.RuntimeNotification(
                level = level, content = summary, toolUseId = origin.sourceToolCallId,
                kind = ContentItem.RuntimeNotification.Kind.TOOL_COMPLETED,
                referenceId = operation.id.value,
                details = buildJsonObject {
                    put("status", phase.name)
                    put("tool_name", operation.invocation.modelToolName)
                    put("source_thread_id", origin.threadId.value)
                    put("origin_readonly", origin.readOnly)
                    outcome.failureCode?.let { put("failure_code", it) }
                    if (outcome.structuredContent != null) put("has_structured_content", true)
                },
                attachments = outputs,
            )),
            createdAt = at,
        )
    }
}
