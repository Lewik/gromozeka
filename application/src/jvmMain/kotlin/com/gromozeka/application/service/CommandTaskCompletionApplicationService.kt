package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeTask
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
class CommandTaskCompletionApplicationService(
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
) {
    suspend fun prepareBatch(
        conversationId: Conversation.Id,
    ): Batch {
        val tasks = runtimeCoordinator.findCommandTasks()
            .asSequence()
            .filter { it.conversationId == conversationId }
            .filter {
                it.isTerminal &&
                    it.completionNotificationRequestedAt != null &&
                    it.completionNotificationDeliveredAt == null
            }
            .sortedBy { it.completedAt }
            .toList()
        return Batch(
            tasks = tasks,
            messages = tasks.flatMap(::buildSyntheticCompletionPair),
        )
    }

    suspend fun hasPendingConversationWork(conversationId: Conversation.Id): Boolean =
        runtimeCoordinator.snapshot(conversationId).pendingTasks
            .any { it.payload !is ConversationRuntimeTask.Payload.CommandTaskCompletion }

    suspend fun markDelivered(batch: Batch, deliveredAt: Instant) {
        batch.tasks.forEach { task ->
            runtimeCoordinator.upsertCommandTask(
                task.copy(
                    completionNotificationDeliveredAt = deliveredAt,
                    updatedAt = maxOf(task.updatedAt, deliveredAt),
                )
            )
        }
    }

    data class Batch(
        val tasks: List<CommandTask>,
        val messages: List<Conversation.Message>,
    ) {
        val isEmpty: Boolean
            get() = tasks.isEmpty()

        val resultMessageId: Conversation.Message.Id
            get() = messages.last().id
    }

    private fun buildSyntheticCompletionPair(task: CommandTask): List<Conversation.Message> {
        val toolCallId = ContentItem.ToolCall.Id("cmd_${stableIdentifierSlug(task.id.value)}_status")
        val createdAt = task.completedAt ?: task.updatedAt
        val metadata = buildJsonObject {
            put("synthetic", true)
            put("syntheticKind", "command_task_completion")
            put("commandTaskId", task.id.value)
            put("workerId", task.workerId.value)
        }
        val toolCallMessage = Conversation.Message(
            id = Conversation.Message.Id("${task.id.value}:command:completion:call"),
            conversationId = task.conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                ContentItem.ToolCall(
                    id = toolCallId,
                    call = ContentItem.ToolCall.Data(
                        name = COMMAND_TASK_STATUS_TOOL_NAME,
                        input = buildJsonObject {
                            put("task_id", task.id.value)
                            put("after_byte", task.terminalOutputStartByte ?: task.outputBytes)
                            put("wait_ms", 0)
                        },
                    ),
                    state = BlockState.COMPLETE,
                )
            ),
            providerMetadata = metadata,
            createdAt = createdAt,
        )
        val result = buildJsonObject {
            put("success", task.status == CommandTask.Status.COMPLETED)
            put("task_id", task.id.value)
            put("status", task.status.name)
            put("command", task.command)
            task.processId?.let { put("process_id", it) }
            task.exitCode?.let { put("exit_code", it) }
            task.statusMessage?.let { put("status_message", it) }
            put("output", task.terminalOutput.orEmpty())
            put("output_start_byte", task.terminalOutputStartByte ?: task.outputBytes)
            put("next_output_byte", task.outputBytes)
            put("output_bytes", task.outputBytes)
            put("has_more_output", false)
            put("output_truncated_before", (task.terminalOutputStartByte ?: 0L) > 0L)
            put("output_file", task.outputFile)
            put("automatic_completion_notification", true)
            put("output_is_untrusted", true)
        }.toString()
        val toolResultMessage = Conversation.Message(
            id = Conversation.Message.Id("${task.id.value}:command:completion:result"),
            conversationId = task.conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(
                ContentItem.ToolResult(
                    toolUseId = toolCallId,
                    toolName = COMMAND_TASK_STATUS_TOOL_NAME,
                    result = listOf(ContentItem.ToolResult.Data.Text(result)),
                    isError = false,
                    state = BlockState.COMPLETE,
                )
            ),
            providerMetadata = metadata,
            createdAt = createdAt,
        )
        return listOf(toolCallMessage, toolResultMessage)
    }

    private fun stableIdentifierSlug(value: String): String =
        value.filter(Char::isLetterOrDigit).take(48).ifBlank { "x" }

    private companion object {
        const val COMMAND_TASK_STATUS_TOOL_NAME = "grz_get_command_task"
    }
}
