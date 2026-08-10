package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
data class CommandTask(
    val id: Id,
    val conversationId: Conversation.Id,
    val workerId: ConversationRuntimeWorkerId,
    val workspaceMountId: WorkspaceMount.Id,
    val agentDefinitionId: AgentDefinition.Id? = null,
    val command: String,
    val workingDirectory: String,
    val status: Status,
    val processId: Long?,
    val processStartedAt: Instant?,
    val processTreeId: Long? = null,
    val outputFile: String,
    val outputBytes: Long,
    val timeoutAt: Instant? = null,
    val cancellationRequestedAt: Instant? = null,
    val exitCode: Int? = null,
    val statusMessage: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    val completionNotificationRequestedAt: Instant? = null,
    val completionNotificationDeliveredAt: Instant? = null,
    val terminalOutputStartByte: Long? = null,
    val terminalOutput: String? = null,
) {
    init {
        require(completionNotificationDeliveredAt == null || completionNotificationRequestedAt != null) {
            "Command completion notification cannot be delivered before it is requested"
        }
        require((terminalOutputStartByte == null) == (terminalOutput == null)) {
            "Command terminal output and its byte offset must be stored together"
        }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    enum class Status {
        WORKING,
        COMPLETED,
        FAILED,
        CANCELLED,
    }

    val isTerminal: Boolean
        get() = status != Status.WORKING
}

@Serializable
data class CommandTaskOutput(
    val task: CommandTask,
    val output: String,
    val outputStartByte: Long,
    val nextOutputByte: Long,
    val hasMoreOutput: Boolean,
)

data class CommandTaskUpsertResult(
    val task: CommandTask,
    val evictedTasks: List<CommandTask> = emptyList(),
)
