package com.gromozeka.server

import com.gromozeka.application.service.AsyncToolOutcomeService
import com.gromozeka.domain.model.AsyncToolOperation
import com.gromozeka.domain.model.AsyncToolOperation.Phase
import com.gromozeka.domain.model.AsyncToolOutcome
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.AsyncToolOperationRepository
import com.gromozeka.domain.repository.WorkerRequestRepository
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.StoredWorkerRequest
import com.gromozeka.remote.protocol.*
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

/** Canonical Worker results are copied before the existing projection/retention ACK can advance. */
@Service
class AsyncWorkerResultProjector(
    private val operations: AsyncToolOperationRepository,
    private val requests: WorkerRequestRepository,
    private val outcomes: AsyncToolOutcomeService,
) : WorkerRequestCompletionObserver {
    private val json = Json { ignoreUnknownKeys = false }

    fun validateChild(operation: AsyncToolOperation, request: StoredWorkerRequest) {
        val origin = operation.invocation.origin
        val target = operation.invocation.target as? ConversationRuntimeTaskTarget.Worker ?: error("Async Worker result has a Server parent")
        check(request.id == operation.id.value && request.asyncTool?.operationId == operation.id &&
            request.workerId == target.workerId && request.actorUserId == origin.actorUserId &&
            request.projectId == origin.projectId && request.slotOrigin == origin.slotOrigin) {
            "Worker result does not match its captured async invocation"
        }
    }

    override suspend fun completed(record: StoredWorkerRequest, response: WorkerGatewayMessage.Response) {
        val reference = record.asyncTool ?: return
        var operation = checkNotNull(operations.find(reference.operationId)) { "Async Worker parent is missing" }
        validateChild(operation, record)
        check(response.requestId == record.id) { "Async Worker response identity mismatch" }
        if (operation.phase.isTerminal) return
        if (record.dispatchedAt != null) {
            operations.recordWorkerDispatch(operation.id)
            operation = checkNotNull(operations.find(operation.id))
        }
        val outcome: AsyncToolOutcome
        val phase: Phase
        if (response.status == WorkerGatewayMessage.Response.Status.SUCCEEDED) {
            check(record.dispatchedAt != null && operation.startedAt != null) { "Async Worker returned success before dispatch" }
            val execution = json.decodeFromString<WorkerToolExecutionResponse>(requireNotNull(response.payload).decodeToString())
            val result = execution.results.single()
            check(result.toolUseId == operation.invocation.origin.sourceToolCallId && result.toolName == operation.invocation.toolCall.call.name) {
                "Async Worker tool result does not match its source call"
            }
            outcome = AsyncToolOutcome(result.result, execution.structuredContentByCallId[result.toolUseId.value], result.isError,
                failureCode = "TOOL_ERROR".takeIf { result.isError })
            phase = if (result.isError) Phase.FAILED else Phase.SUCCEEDED
        } else {
            phase = when {
                record.dispatchedAt != null -> Phase.OUTCOME_UNKNOWN
                response.errorCode == "CANCELLED" -> Phase.CANCELLED
                else -> Phase.FAILED
            }
            outcome = AsyncToolOutcome(
                content = listOf(ContentItem.ToolResult.Data.Text(requireNotNull(response.errorMessage))),
                isError = true, failureCode = requireNotNull(response.errorCode),
            )
        }
        outcomes.finish(operation, phase, outcome)
    }

    suspend fun reconcile(operation: AsyncToolOperation) {
        if (operation.phase.isTerminal) return
        val request = requests.find(operation.id.value)
        if (request == null) {
            if (operation.workerRequestCreatedAt != null) outcomes.finish(operation, Phase.OUTCOME_UNKNOWN,
                AsyncToolOutcome(isError = true, failureCode = "WORKER_REQUEST_UNAVAILABLE",
                    content = listOf(ContentItem.ToolResult.Data.Text("The durable Worker request is unavailable. Its action may have run; it is not submitted again."))))
            return
        }
        validateChild(operation, request)
        if (request.dispatchedAt != null) operations.recordWorkerDispatch(operation.id)
        val response = request.response
        if (response != null) {
            completed(request, WorkerGatewayCodec.decode(response) as WorkerGatewayMessage.Response)
        } else request.executionDeadline?.let { deadline ->
            operations.renewLease(operation.id, checkNotNull(operation.executorSessionId), deadline)
        }
    }
}
