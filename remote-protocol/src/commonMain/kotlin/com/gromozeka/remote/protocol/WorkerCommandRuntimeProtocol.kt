package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandMonitorLifecycleEvent
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskLifecycleEvent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@Serializable
sealed interface WorkerCommandRuntimeRequest {
    @Serializable
    @SerialName("upsert_command_task")
    data class UpsertCommandTask(
        val task: CommandTask,
    ) : WorkerCommandRuntimeRequest

    @Serializable
    @SerialName("find_command_tasks")
    data object FindCommandTasks : WorkerCommandRuntimeRequest

    @Serializable
    @SerialName("find_command_task")
    data class FindCommandTask(
        val conversationId: Conversation.Id,
        val taskId: CommandTask.Id,
    ) : WorkerCommandRuntimeRequest

    @Serializable
    @SerialName("synchronize_command_monitor")
    data class SynchronizeCommandMonitor(
        val monitor: CommandMonitor,
        val events: List<CommandMonitorEvent>,
    ) : WorkerCommandRuntimeRequest

    @Serializable
    @SerialName("find_command_monitors")
    data object FindCommandMonitors : WorkerCommandRuntimeRequest

    @Serializable
    @SerialName("find_command_monitor")
    data class FindCommandMonitor(
        val conversationId: Conversation.Id,
        val monitorId: CommandMonitor.Id,
    ) : WorkerCommandRuntimeRequest

    @Serializable
    @SerialName("find_command_monitor_events")
    data class FindCommandMonitorEvents(
        val conversationId: Conversation.Id,
        val monitorId: CommandMonitor.Id,
    ) : WorkerCommandRuntimeRequest

    @Serializable
    @SerialName("publish_command_task_lifecycle")
    data class PublishCommandTaskLifecycle(
        val event: CommandTaskLifecycleEvent,
    ) : WorkerCommandRuntimeRequest

    @Serializable
    @SerialName("publish_command_monitor_lifecycle")
    data class PublishCommandMonitorLifecycle(
        val event: CommandMonitorLifecycleEvent,
    ) : WorkerCommandRuntimeRequest

    @Serializable
    @SerialName("publish_snapshot")
    data class PublishSnapshot(
        val conversationId: Conversation.Id,
    ) : WorkerCommandRuntimeRequest
}

@Serializable
sealed interface WorkerCommandRuntimeResponse {
    @Serializable
    @SerialName("completed")
    data object Completed : WorkerCommandRuntimeResponse

    @Serializable
    @SerialName("command_task")
    data class CommandTaskResult(
        val task: CommandTask?,
    ) : WorkerCommandRuntimeResponse

    @Serializable
    @SerialName("command_tasks")
    data class CommandTasksResult(
        val tasks: List<CommandTask>,
    ) : WorkerCommandRuntimeResponse

    @Serializable
    @SerialName("command_task_upserted")
    data class CommandTaskUpserted(
        val evictedTasks: List<CommandTask>,
    ) : WorkerCommandRuntimeResponse

    @Serializable
    @SerialName("command_monitor")
    data class CommandMonitorResult(
        val monitor: CommandMonitor?,
    ) : WorkerCommandRuntimeResponse

    @Serializable
    @SerialName("command_monitors")
    data class CommandMonitorsResult(
        val monitors: List<CommandMonitor>,
    ) : WorkerCommandRuntimeResponse

    @Serializable
    @SerialName("command_monitor_events")
    data class CommandMonitorEventsResult(
        val events: List<CommandMonitorEvent>,
    ) : WorkerCommandRuntimeResponse

    @Serializable
    @SerialName("command_monitor_synchronized")
    data class CommandMonitorSynchronized(
        val monitor: CommandMonitor,
        val evictedMonitors: List<CommandMonitor>,
    ) : WorkerCommandRuntimeResponse
}

@OptIn(ExperimentalSerializationApi::class)
object WorkerCommandRuntimeGatewayCodec {
    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encodeRequest(request: WorkerCommandRuntimeRequest): ByteArray =
        cbor.encodeToByteArray(WorkerCommandRuntimeRequest.serializer(), request)

    fun decodeRequest(payload: ByteArray): WorkerCommandRuntimeRequest =
        cbor.decodeFromByteArray(WorkerCommandRuntimeRequest.serializer(), payload)

    fun encodeResponse(response: WorkerCommandRuntimeResponse): ByteArray =
        cbor.encodeToByteArray(WorkerCommandRuntimeResponse.serializer(), response)

    fun decodeResponse(payload: ByteArray): WorkerCommandRuntimeResponse =
        cbor.decodeFromByteArray(WorkerCommandRuntimeResponse.serializer(), payload)
}
