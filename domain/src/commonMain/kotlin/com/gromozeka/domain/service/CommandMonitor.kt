package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
data class CommandMonitor(
    val id: Id,
    val conversationId: Conversation.Id,
    val commandTaskId: CommandTask.Id,
    val workerId: ConversationRuntimeWorkerId,
    val workspaceMountId: WorkspaceMount.Id,
    val agentDefinitionId: AgentDefinition.Id? = null,
    val filterCommand: String,
    val mode: Mode,
    val startFrom: StartFrom,
    val status: Status,
    val sourceOutputCursor: Long,
    val processId: Long?,
    val processStartedAt: Instant?,
    val processTreeId: Long? = null,
    val outputFile: String,
    val errorFile: String,
    val outputBytes: Long,
    val eventOutputCursor: Long,
    val eventCount: Long = 0,
    val lastEventAt: Instant? = null,
    val lastEventPreview: String? = null,
    val cancellationRequestedAt: Instant? = null,
    val exitCode: Int? = null,
    val statusMessage: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    val terminalNotificationRequestedAt: Instant? = null,
    val terminalNotificationDeliveredAt: Instant? = null,
    val terminalOutputStartByte: Long? = null,
    val terminalOutput: String? = null,
    val terminalErrorOutput: String? = null,
) {
    init {
        require(filterCommand.isNotBlank()) { "Command monitor filter command must not be blank" }
        require(sourceOutputCursor >= 0) { "Command monitor source cursor must be non-negative" }
        require(outputBytes >= 0) { "Command monitor output size must be non-negative" }
        require(eventOutputCursor in 0..outputBytes) {
            "Command monitor event cursor must be within captured output"
        }
        require(eventCount >= 0) { "Command monitor event count must be non-negative" }
        require(terminalNotificationDeliveredAt == null || terminalNotificationRequestedAt != null) {
            "Command monitor terminal notification cannot be delivered before it is requested"
        }
        require((terminalOutputStartByte == null) == (terminalOutput == null)) {
            "Command monitor terminal output and its byte offset must be stored together"
        }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    enum class Mode {
        ONCE,
        CONTINUOUS,
    }

    @Serializable
    enum class StartFrom {
        NOW,
        BEGINNING,
    }

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
data class CommandMonitorEvent(
    val id: Id,
    val conversationId: Conversation.Id,
    val monitorId: CommandMonitor.Id,
    val outputStartByte: Long,
    val outputEndByte: Long,
    val output: String,
    val outputTruncatedBefore: Boolean,
    val occurredAt: Instant,
    val deliveryRequested: Boolean,
    val deliveredAt: Instant? = null,
) {
    init {
        require(outputStartByte >= 0) { "Command monitor event start must be non-negative" }
        require(outputEndByte > outputStartByte) { "Command monitor event must consume output bytes" }
        require(deliveredAt == null || deliveryRequested) {
            "Command monitor event cannot be delivered when automatic delivery was not requested"
        }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String)
}

@Serializable
data class CommandMonitorOutput(
    val monitor: CommandMonitor,
    val output: String,
    val outputStartByte: Long,
    val nextOutputByte: Long,
    val hasMoreOutput: Boolean,
)

data class CommandMonitorSyncResult(
    val monitor: CommandMonitor,
    val evictedMonitors: List<CommandMonitor> = emptyList(),
)
