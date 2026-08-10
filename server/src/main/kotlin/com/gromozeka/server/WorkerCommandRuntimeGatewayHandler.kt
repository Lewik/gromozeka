package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandRuntimeStateService
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.remote.protocol.WorkerCommandRuntimeGatewayCodec
import com.gromozeka.remote.protocol.WorkerCommandRuntimeRequest
import com.gromozeka.remote.protocol.WorkerCommandRuntimeResponse
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import org.springframework.stereotype.Service

interface WorkerGatewayServerRequestHandler {
    val operation: WorkerGatewayOperation

    suspend fun execute(
        identity: ConversationRuntimeWorkerIdentity,
        request: WorkerGatewayMessage.Request,
    ): ByteArray
}

@Service
class WorkerCommandRuntimeGatewayHandler(
    private val commandRuntimeStateService: CommandRuntimeStateService,
    private val conversationRepository: ConversationRepository,
    private val workspaceDomainService: WorkspaceDomainService,
    private val workerAccessService: WorkerAccessService,
) : WorkerGatewayServerRequestHandler {
    override val operation = WorkerGatewayOperation.COMMAND_RUNTIME_STATE

    override suspend fun execute(
        identity: ConversationRuntimeWorkerIdentity,
        request: WorkerGatewayMessage.Request,
    ): ByteArray {
        require(request.operation == operation) {
            "Worker cannot invoke Server operation ${request.operation}"
        }
        val response = when (
            val runtimeRequest = WorkerCommandRuntimeGatewayCodec.decodeRequest(request.payload)
        ) {
            is WorkerCommandRuntimeRequest.UpsertCommandTask -> {
                val task = validateCommandTask(identity.workerId, runtimeRequest.task)
                val result = commandRuntimeStateService.upsertCommandTask(task)
                WorkerCommandRuntimeResponse.CommandTaskUpserted(result.task, result.evictedTasks)
            }

            WorkerCommandRuntimeRequest.FindCommandTasks ->
                WorkerCommandRuntimeResponse.CommandTasksResult(
                    commandRuntimeStateService.findCommandTasks()
                        .filter { it.workerId == identity.workerId }
                )

            is WorkerCommandRuntimeRequest.FindCommandTask ->
                WorkerCommandRuntimeResponse.CommandTaskResult(
                    commandRuntimeStateService.findCommandTask(
                        runtimeRequest.conversationId,
                        runtimeRequest.taskId,
                    )
                        ?.takeIf { it.workerId == identity.workerId }
                )

            is WorkerCommandRuntimeRequest.SynchronizeCommandMonitor -> {
                val monitor = validateCommandMonitor(
                    identity.workerId,
                    runtimeRequest.monitor,
                    runtimeRequest.events,
                )
                val result = commandRuntimeStateService.synchronizeCommandMonitor(
                    monitor,
                    runtimeRequest.events,
                )
                WorkerCommandRuntimeResponse.CommandMonitorSynchronized(
                    monitor = result.monitor,
                    evictedMonitors = result.evictedMonitors,
                )
            }

            WorkerCommandRuntimeRequest.FindCommandMonitors ->
                WorkerCommandRuntimeResponse.CommandMonitorsResult(
                    commandRuntimeStateService.findCommandMonitors()
                        .filter { it.workerId == identity.workerId }
                )

            is WorkerCommandRuntimeRequest.FindCommandMonitor ->
                WorkerCommandRuntimeResponse.CommandMonitorResult(
                    commandRuntimeStateService.findCommandMonitor(
                        runtimeRequest.conversationId,
                        runtimeRequest.monitorId,
                    )
                        ?.takeIf { it.workerId == identity.workerId }
                )

            is WorkerCommandRuntimeRequest.FindCommandMonitorEvents -> {
                val monitor = commandRuntimeStateService.findCommandMonitor(
                    runtimeRequest.conversationId,
                    runtimeRequest.monitorId,
                )
                require(monitor?.workerId == identity.workerId) {
                    "Worker cannot read events for a command monitor it does not own"
                }
                WorkerCommandRuntimeResponse.CommandMonitorEventsResult(
                    commandRuntimeStateService.findCommandMonitorEvents(
                        runtimeRequest.conversationId,
                        runtimeRequest.monitorId,
                    )
                )
            }

            is WorkerCommandRuntimeRequest.PublishSnapshot -> {
                requireConversationAccess(identity.workerId, runtimeRequest.conversationId)
                commandRuntimeStateService.publishSnapshot(runtimeRequest.conversationId)
                WorkerCommandRuntimeResponse.Completed
            }
        }
        return WorkerCommandRuntimeGatewayCodec.encodeResponse(response)
    }

    private suspend fun validateCommandTask(
        workerId: ConversationRuntimeWorkerId,
        requested: CommandTask,
    ): CommandTask {
        require(requested.workerId == workerId) {
            "Worker cannot write a command task for another Worker"
        }
        requireExecutionScope(workerId, requested.conversationId, requested.workspaceMountId)
        val existing = commandRuntimeStateService.findCommandTask(requested.conversationId, requested.id)
            ?: return requested
        require(existing.workerId == workerId) {
            "Worker cannot replace a command task owned by another Worker"
        }
        require(existing.immutableIdentity() == requested.immutableIdentity()) {
            "Worker cannot change command task identity or execution scope"
        }
        require(!existing.isTerminal || requested.status == existing.status) {
            "Worker cannot move a terminal command task to another state"
        }
        return requested
    }

    private suspend fun validateCommandMonitor(
        workerId: ConversationRuntimeWorkerId,
        requested: CommandMonitor,
        events: List<CommandMonitorEvent>,
    ): CommandMonitor {
        require(requested.workerId == workerId) {
            "Worker cannot write a command monitor for another Worker"
        }
        requireExecutionScope(workerId, requested.conversationId, requested.workspaceMountId)
        val sourceTask = commandRuntimeStateService.findCommandTask(
            requested.conversationId,
            requested.commandTaskId,
        )
        require(sourceTask?.workerId == workerId && sourceTask.workspaceMountId == requested.workspaceMountId) {
            "Command monitor source task does not belong to this Worker and mount"
        }
        require(events.all { it.conversationId == requested.conversationId && it.monitorId == requested.id }) {
            "Command monitor events must belong to the synchronized monitor"
        }
        val existing = commandRuntimeStateService.findCommandMonitor(requested.conversationId, requested.id)
            ?: return requested
        require(existing.workerId == workerId) {
            "Worker cannot replace a command monitor owned by another Worker"
        }
        require(existing.immutableIdentity() == requested.immutableIdentity()) {
            "Worker cannot change command monitor identity or execution scope"
        }
        require(!existing.isTerminal || requested.status == existing.status) {
            "Worker cannot move a terminal command monitor to another state"
        }
        return requested
    }

    private suspend fun requireExecutionScope(
        workerId: ConversationRuntimeWorkerId,
        conversationId: Conversation.Id,
        workspaceMountId: com.gromozeka.domain.model.WorkspaceMount.Id,
    ) {
        val conversation = requireConversationAccess(workerId, conversationId)
        val execution = workspaceDomainService.resolveExecution(workspaceMountId)
        require(execution.mount.workerId == workerId.value) {
            "Workspace mount is assigned to another Worker"
        }
        require(execution.project.id == conversation.projectId) {
            "Workspace mount and conversation belong to different projects"
        }
    }

    private suspend fun requireConversationAccess(
        workerId: ConversationRuntimeWorkerId,
        conversationId: Conversation.Id,
    ): Conversation {
        val conversation = conversationRepository.findById(conversationId)
            ?: error("Conversation not found: ${conversationId.value}")
        workerAccessService.requireProjectAccess(workerId, conversation.projectId)
        return conversation
    }

    private fun CommandTask.immutableIdentity(): CommandTaskIdentity =
        CommandTaskIdentity(
            id = id,
            conversationId = conversationId,
            workerId = workerId,
            workspaceMountId = workspaceMountId,
            agentDefinitionId = agentDefinitionId,
            command = command,
            workingDirectory = workingDirectory,
            processId = processId,
            processStartedAt = processStartedAt,
            processTreeId = processTreeId,
            outputFile = outputFile,
            timeoutAt = timeoutAt,
            createdAt = createdAt,
        )

    private fun CommandMonitor.immutableIdentity(): CommandMonitorIdentity =
        CommandMonitorIdentity(
            id = id,
            conversationId = conversationId,
            commandTaskId = commandTaskId,
            workerId = workerId,
            workspaceMountId = workspaceMountId,
            agentDefinitionId = agentDefinitionId,
            filterCommand = filterCommand,
            mode = mode,
            startFrom = startFrom,
            processId = processId,
            processStartedAt = processStartedAt,
            processTreeId = processTreeId,
            outputFile = outputFile,
            errorFile = errorFile,
            createdAt = createdAt,
        )

    private data class CommandTaskIdentity(
        val id: CommandTask.Id,
        val conversationId: Conversation.Id,
        val workerId: ConversationRuntimeWorkerId,
        val workspaceMountId: com.gromozeka.domain.model.WorkspaceMount.Id,
        val agentDefinitionId: com.gromozeka.domain.model.AgentDefinition.Id?,
        val command: String,
        val workingDirectory: String,
        val processId: Long?,
        val processStartedAt: kotlin.time.Instant?,
        val processTreeId: Long?,
        val outputFile: String,
        val timeoutAt: kotlin.time.Instant?,
        val createdAt: kotlin.time.Instant,
    )

    private data class CommandMonitorIdentity(
        val id: CommandMonitor.Id,
        val conversationId: Conversation.Id,
        val commandTaskId: CommandTask.Id,
        val workerId: ConversationRuntimeWorkerId,
        val workspaceMountId: com.gromozeka.domain.model.WorkspaceMount.Id,
        val agentDefinitionId: com.gromozeka.domain.model.AgentDefinition.Id?,
        val filterCommand: String,
        val mode: CommandMonitor.Mode,
        val startFrom: CommandMonitor.StartFrom,
        val processId: Long?,
        val processStartedAt: kotlin.time.Instant?,
        val processTreeId: Long?,
        val outputFile: String,
        val errorFile: String,
        val createdAt: kotlin.time.Instant,
    )
}
