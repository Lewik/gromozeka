package com.gromozeka.worker

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandMonitorSyncResult
import com.gromozeka.domain.service.CommandRuntimeStateService
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskUpsertResult
import com.gromozeka.remote.protocol.WorkerCommandRuntimeGatewayCodec
import com.gromozeka.remote.protocol.WorkerCommandRuntimeRequest
import com.gromozeka.remote.protocol.WorkerCommandRuntimeResponse
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import org.springframework.stereotype.Service

@Service
class WorkerGatewayCommandRuntimeStateService(
    private val outbound: WorkerGatewayOutbound,
) : CommandRuntimeStateService {
    override suspend fun upsertCommandTask(task: CommandTask): CommandTaskUpsertResult {
        val response = execute(WorkerCommandRuntimeRequest.UpsertCommandTask(task))
        check(response is WorkerCommandRuntimeResponse.CommandTaskUpserted) {
            "Unexpected command task upsert response: ${response::class.simpleName}"
        }
        return CommandTaskUpsertResult(response.task, response.evictedTasks)
    }

    override suspend fun findCommandTasks(): List<CommandTask> {
        val response = execute(WorkerCommandRuntimeRequest.FindCommandTasks)
        check(response is WorkerCommandRuntimeResponse.CommandTasksResult) {
            "Unexpected command tasks response: ${response::class.simpleName}"
        }
        return response.tasks
    }

    override suspend fun findCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): CommandTask? {
        val response = execute(
            WorkerCommandRuntimeRequest.FindCommandTask(conversationId, taskId)
        )
        check(response is WorkerCommandRuntimeResponse.CommandTaskResult) {
            "Unexpected command task response: ${response::class.simpleName}"
        }
        return response.task
    }

    override suspend fun synchronizeCommandMonitor(
        monitor: CommandMonitor,
        events: List<CommandMonitorEvent>,
    ): CommandMonitorSyncResult {
        val response = execute(
            WorkerCommandRuntimeRequest.SynchronizeCommandMonitor(monitor, events)
        )
        check(response is WorkerCommandRuntimeResponse.CommandMonitorSynchronized) {
            "Unexpected command monitor synchronization response: ${response::class.simpleName}"
        }
        return CommandMonitorSyncResult(response.monitor, response.evictedMonitors)
    }

    override suspend fun findCommandMonitors(): List<CommandMonitor> {
        val response = execute(WorkerCommandRuntimeRequest.FindCommandMonitors)
        check(response is WorkerCommandRuntimeResponse.CommandMonitorsResult) {
            "Unexpected command monitors response: ${response::class.simpleName}"
        }
        return response.monitors
    }

    override suspend fun findCommandMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): CommandMonitor? {
        val response = execute(
            WorkerCommandRuntimeRequest.FindCommandMonitor(conversationId, monitorId)
        )
        check(response is WorkerCommandRuntimeResponse.CommandMonitorResult) {
            "Unexpected command monitor response: ${response::class.simpleName}"
        }
        return response.monitor
    }

    override suspend fun findCommandMonitorEvents(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): List<CommandMonitorEvent> {
        val response = execute(
            WorkerCommandRuntimeRequest.FindCommandMonitorEvents(conversationId, monitorId)
        )
        check(response is WorkerCommandRuntimeResponse.CommandMonitorEventsResult) {
            "Unexpected command monitor events response: ${response::class.simpleName}"
        }
        return response.events
    }

    override suspend fun publishSnapshot(conversationId: Conversation.Id) {
        requireCompleted(
            execute(WorkerCommandRuntimeRequest.PublishSnapshot(conversationId))
        )
    }

    private suspend fun execute(request: WorkerCommandRuntimeRequest): WorkerCommandRuntimeResponse =
        WorkerCommandRuntimeGatewayCodec.decodeResponse(
            outbound.execute(
                operation = WorkerGatewayOperation.COMMAND_RUNTIME_STATE,
                payload = WorkerCommandRuntimeGatewayCodec.encodeRequest(request),
            )
        )

    private fun requireCompleted(response: WorkerCommandRuntimeResponse) {
        check(response == WorkerCommandRuntimeResponse.Completed) {
            "Unexpected command runtime completion response: ${response::class.simpleName}"
        }
    }
}
