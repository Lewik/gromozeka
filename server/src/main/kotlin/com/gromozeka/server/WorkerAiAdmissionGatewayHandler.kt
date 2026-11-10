package com.gromozeka.server

import com.gromozeka.application.service.ServerAiCallAdmission
import com.gromozeka.domain.repository.WorkerRequestRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service
import kotlin.time.Clock

@Service
class WorkerAiAdmissionGatewayHandler(
    private val admission: ServerAiCallAdmission,
    private val requests: WorkerRequestRepository,
) : WorkerGatewayServerRequestHandler, WorkerExecutionCompletionObserver {
    override val operation = WorkerGatewayOperation.AI_ADMISSION

    override suspend fun execute(identity: ConversationRuntimeWorkerIdentity, request: WorkerGatewayMessage.Request): ByteArray {
        require(request.operation == operation)
        return when (val command = AiAdmissionGatewayCodec.decode(request.payload)) {
            is AiAdmissionCommand.Acquire -> {
                val owner = requireNotNull(requests.find(command.workerRequestId)) { "Owning Worker request is unavailable" }
                require(owner.workerId == identity.workerId && owner.dispatchedAt != null && owner.completedAt == null && owner.cancelRequestedAt == null) {
                    "AI admission requires an active request owned by this Worker"
                }
                val original = WorkerGatewayCodec.decode(owner.request) as WorkerGatewayMessage.Request
                require(original.operation in setOf(WorkerGatewayOperation.AI_REQUEST_RESPONSE,
                    WorkerGatewayOperation.TOOL_EXECUTION, WorkerGatewayOperation.AUDIO_CAPTURE)) {
                    "This Worker request cannot execute AI operations"
                }
                command.operation.backgroundSessionId?.let { sessionId ->
                    require(original.operation == WorkerGatewayOperation.AUDIO_CAPTURE) { "Only a capture start may own a background AI admission" }
                    val capture = WorkerAudioCaptureGatewayCodec.decodeRequest(original.payload)
                    val start = requireNotNull(capture.command as? com.gromozeka.domain.service.WorkerAudioCaptureRequest.Command.StartClaudeCodeMicrophone) {
                        "Background AI admission requires a Claude Code microphone start"
                    }
                    require(capture.target == identity && start.sessionId == sessionId &&
                        command.operation.target == com.gromozeka.domain.model.ai.AiAdmissionTarget.Connection(start.connection.id)) {
                        "Background AI admission does not match its capture start"
                    }
                }
                val ownerDeadline = requireNotNull(owner.executionDeadline) { "Owning Worker request has no execution deadline" }
                val deadline = command.operation.deadline?.let { minOf(it, ownerDeadline) } ?: ownerDeadline
                val remaining = (deadline - Clock.System.now()).inWholeMilliseconds
                require(remaining > 0) { "Owning AI operation deadline has passed" }
                withTimeout(remaining) {
                    AiAdmissionGatewayCodec.encodeLease(admission.acquireRemote(
                        command.operation.copy(deadline = deadline), identity.workerId.value, owner.id,
                    ))
                }
            }
            is AiAdmissionCommand.Accept -> {
                admission.acceptOperation(command.operationId, identity.workerId.value)
                byteArrayOf()
            }
            is AiAdmissionCommand.Finish -> {
                admission.finishOperation(command.operationId, identity.workerId.value, command.outcome)
                byteArrayOf()
            }
        }
    }

    override suspend fun executionCompleted(identity: ConversationRuntimeWorkerIdentity, response: WorkerGatewayMessage.Response) {
        admission.workerRequestFinished(identity.workerId.value, response.requestId)
    }
}
