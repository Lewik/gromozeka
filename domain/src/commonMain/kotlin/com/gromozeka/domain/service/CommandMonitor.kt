package com.gromozeka.domain.service

import com.gromozeka.domain.model.BinaryContent
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import kotlin.time.Instant
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
    val synchronizationError: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    val terminalNotificationRequestedAt: Instant? = null,
    val terminalNotificationDeliveredAt: Instant? = null,
    val terminalOutputStartByte: Long? = null,
    val terminalOutputContent: BinaryContent? = null,
    val terminalErrorContent: BinaryContent? = null,
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
        require((terminalOutputStartByte == null) == (terminalOutputContent == null)) {
            "Command monitor terminal output and its byte offset must be stored together"
        }
    }

    val terminalOutput: String? get() = terminalOutputContent?.textPreview()
    val terminalErrorOutput: String? get() = terminalErrorContent?.textPreview()

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
    val content: BinaryContent,
    val outputTruncatedBefore: Boolean,
    val occurredAt: Instant,
    val deliveryRequested: Boolean,
    val deliveredAt: Instant? = null,
) {
    val output: String get() = content.textPreview().removeSuffix("\n").removeSuffix("\r")

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
    val content: BinaryContent,
    val outputStartByte: Long,
    val nextOutputByte: Long,
    val hasMoreOutput: Boolean,
) {
    val output: String get() = content.textPreview()
}

data class CommandMonitorSyncResult(
    val monitor: CommandMonitor,
    val evictedMonitors: List<CommandMonitor> = emptyList(),
)
