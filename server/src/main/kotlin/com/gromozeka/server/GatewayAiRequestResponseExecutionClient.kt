package com.gromozeka.server

import com.gromozeka.domain.model.ai.AiContextInspection
import com.gromozeka.domain.model.ai.AiConnectionModels
import com.gromozeka.domain.model.ai.requireGatewayModelListing
import com.gromozeka.domain.model.ai.AiCallException
import com.gromozeka.domain.model.ai.AiCallFailure
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiHttpCallBudget
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.domain.service.WorkerRequestPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaRequest
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaSnapshot
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.AiSpeechSynthesisRequest
import com.gromozeka.domain.service.AiSpeechSynthesisResponse
import com.gromozeka.domain.service.AiSpeechTranscriptionRequest
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.remote.protocol.AiRequestResponseGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.time.Duration

@Service
@Primary
class GatewayAiRequestResponseExecutionClient(
    private val requests: WorkerRequestService,
    @Value("\${gromozeka.runtime.ai-request-response.timeout-millis:1800000}")
    timeoutMillis: Long,
) : AiRequestResponseExecutionClient {
    private val log = KLoggers.logger(this)
    private val timeout = Duration.ofMillis(timeoutMillis)

    init {
        require(timeoutMillis > 0) { "AI request-response timeout must be positive" }
    }

    override suspend fun listModels(target: ConversationRuntimeWorkerIdentity, connection: AiConnection,
        deadline: Instant, actorUserId: com.gromozeka.domain.model.User.Id): AiConnectionModels {
        connection.requireGatewayModelListing()
        require((connection.executionTarget as? com.gromozeka.domain.model.ai.AiExecutionTarget.Worker)?.workerId == target.workerId.value) {
            "Model-list transport target does not match the connection"
        }
        val (id, payload) = executeCall(target, AiRequestResponseGatewayCodec.encodeModelsRequest(connection, deadline), deadline, actorUserId)
        return try { AiRequestResponseGatewayCodec.decodeModelsResponse(payload) }
        catch (error: AiCallException) { throw AiCallException(error.failure.copy(workerRequestId = id)) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw AiCallException(AiCallFailure(AiCallFailure.Kind.WORKER_FAILURE,
            AiCallFailure.Outcome.UNKNOWN, null, workerRequestId = id)) }
    }

    override suspend fun inspectContext(target: ConversationRuntimeWorkerIdentity, runtime: ResolvedAiRuntime,
        workspaceRootPath: String?, request: AiRuntimeRequest, allowProviderCount: Boolean): AiContextInspection =
        contextOperation(target, runtime, workspaceRootPath, request,
            { resolved, root, input -> AiRequestResponseGatewayCodec.encodeContextInspectionRequest(resolved, root, input, allowProviderCount) },
            AiRequestResponseGatewayCodec::decodeContextInspectionResponse)

    override suspend fun compact(target: ConversationRuntimeWorkerIdentity, runtime: ResolvedAiRuntime,
        workspaceRootPath: String?, request: AiRuntimeRequest): AiRuntimeResponse =
        contextOperation(target, runtime, workspaceRootPath, request,
            AiRequestResponseGatewayCodec::encodeCompactionRequest, AiRequestResponseGatewayCodec::decodeCallResponse)

    private suspend fun <T> contextOperation(target: ConversationRuntimeWorkerIdentity, runtime: ResolvedAiRuntime,
        workspaceRootPath: String?, request: AiRuntimeRequest,
        encode: (ResolvedAiRuntime, String?, AiRuntimeRequest) -> ByteArray, decode: (ByteArray) -> T): T {
        val now = Clock.System.now()
        val deadline = minOf(AiHttpCallBudget.deadline(runtime.modelConfiguration.defaultParameters.timeoutSeconds,
            request.options.executionDeadline, now), now + timeout.toMillis().milliseconds)
        val payload = encode(runtime, workspaceRootPath, request.copy(options = request.options.copy(executionDeadline = deadline)))
        val (id, response) = executeCall(target, payload, deadline)
        return try { decode(response) }
        catch (error: AiCallException) { throw AiCallException(error.failure.copy(workerRequestId = id)) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw AiCallException(AiCallFailure(AiCallFailure.Kind.WORKER_FAILURE,
            AiCallFailure.Outcome.UNKNOWN, null, workerRequestId = id)) }
    }

    override suspend fun call(
        target: ConversationRuntimeWorkerIdentity,
        runtime: ResolvedAiRuntime,
        workspaceRootPath: String?,
        request: AiRuntimeRequest,
    ): AiRuntimeResponse {
        val diagnosticId = (request.options.toolContext["aiCallDiagnosticId"] as? String)
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,80}")) }
            ?: uuid7().toString()
        val startedAt = System.nanoTime()
        val now = Clock.System.now()
        val deadline = if (runtime.connection is AiConnection.ConfigurableHttpAiConnection)
            minOf(AiHttpCallBudget.deadline(runtime.modelConfiguration.defaultParameters.timeoutSeconds,
                request.options.executionDeadline, now), now + timeout.toMillis().milliseconds)
        else null
        val tracedRequest = request.copy(options = request.options.copy(
            toolContext = request.options.toolContext + ("aiCallDiagnosticId" to diagnosticId),
            executionDeadline = deadline ?: request.options.executionDeadline,
        ))
        val payload = AiRequestResponseGatewayCodec.encodeCallRequest(runtime, workspaceRootPath, tracedRequest)
        log.debug {
            "AI_GATEWAY_TRACE call=$diagnosticId phase=encoded worker=${target.workerId.value} " +
                "provider=${runtime.connection.kind} bytes=${payload.size} " +
                "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}"
        }
        return try {
            val (workerRequestId, response) = if (deadline == null) null to execute(target, payload, diagnosticId)
                else executeCall(target, payload, deadline)
            log.debug {
                "AI_GATEWAY_TRACE call=$diagnosticId phase=response_received bytes=${response.size} " +
                    "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}"
            }
            try { AiRequestResponseGatewayCodec.decodeCallResponse(response) }
            catch (error: AiCallException) {
                throw AiCallException(error.failure.copy(workerRequestId = workerRequestId ?: error.failure.workerRequestId))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (deadline == null) throw error
                throw AiCallException(AiCallFailure(AiCallFailure.Kind.WORKER_FAILURE,
                    AiCallFailure.Outcome.UNKNOWN, null, workerRequestId = workerRequestId))
            }
        } finally {
            log.debug {
                "AI_GATEWAY_TRACE call=$diagnosticId phase=finished elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}"
            }
        }
    }

    override suspend fun embed(
        target: ConversationRuntimeWorkerIdentity,
        runtime: ResolvedAiRuntime,
        request: AiEmbeddingRequest,
    ): AiEmbeddingResponse =
        execute(target, AiRequestResponseGatewayCodec.encodeEmbeddingRequest(runtime, request))
            .let(AiRequestResponseGatewayCodec::decodeEmbeddingResponse)

    override suspend fun transcribe(
        target: ConversationRuntimeWorkerIdentity,
        runtime: ResolvedAiRuntime?,
        localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
        request: AiSpeechTranscriptionRequest,
    ): String =
        execute(
            target,
            AiRequestResponseGatewayCodec.encodeTranscriptionRequest(
                runtime,
                localWhisperSettings,
                request,
            ),
        )
            .let(AiRequestResponseGatewayCodec::decodeTranscriptionResponse)

    override suspend fun synthesize(
        target: ConversationRuntimeWorkerIdentity,
        runtime: ResolvedAiRuntime,
        request: AiSpeechSynthesisRequest,
    ): AiSpeechSynthesisResponse =
        execute(target, AiRequestResponseGatewayCodec.encodeSynthesisRequest(runtime, request))
            .let(AiRequestResponseGatewayCodec::decodeSynthesisResponse)

    override suspend fun readSubscriptionQuota(
        target: ConversationRuntimeWorkerIdentity,
        request: AiSubscriptionQuotaRequest,
    ): AiSubscriptionQuotaSnapshot =
        execute(target, AiRequestResponseGatewayCodec.encodeSubscriptionQuotaRequest(request))
            .let(AiRequestResponseGatewayCodec::decodeSubscriptionQuotaResponse)

    private suspend fun executeCall(target: ConversationRuntimeWorkerIdentity, payload: ByteArray, deadline: Instant,
        actorUserId: com.gromozeka.domain.model.User.Id? = null): Pair<String, ByteArray> {
        val remaining = (deadline - Clock.System.now()).inWholeMilliseconds
        if (remaining <= 0) throw AiCallException(AiCallFailure(AiCallFailure.Kind.DEADLINE_EXCEEDED,
            AiCallFailure.Outcome.NOT_STARTED, 0))
        var requestId: String? = null
        try {
            return withTimeout(remaining) {
                currentCoroutineContext().ensureActive()
                // Keep the id even if cancellation races with the durable insert returning.
                withContext(NonCancellable) {
                    requestId = requests.submit(target.workerId, WorkerGatewayOperation.AI_REQUEST_RESPONSE, payload,
                        WorkerRequestPolicy(deliveryTtlMillis = minOf(30_000, remaining),
                            executionTimeoutMillis = remaining, waitTimeoutMillis = remaining), actorUserId = actorUserId)
                }
                val id = requireNotNull(requestId)
                val response = requests.await(id, remaining)
                if (response.status != WorkerGatewayMessage.Response.Status.SUCCEEDED) {
                    val neverStarted = response.errorCode == "EXPIRED"
                    throw AiCallException(AiCallFailure(AiCallFailure.Kind.WORKER_FAILURE,
                        if (neverStarted) AiCallFailure.Outcome.NOT_STARTED else AiCallFailure.Outcome.UNKNOWN,
                        if (neverStarted) 0 else null, workerRequestId = id))
                }
                id to requireNotNull(response.payload)
            }
        } catch (error: CancellationException) {
            requestId?.let { id -> withContext(NonCancellable) { requests.cancel(id) } }
            currentCoroutineContext().ensureActive()
            if (error !is TimeoutCancellationException) throw error
            throw AiCallException(AiCallFailure(AiCallFailure.Kind.DEADLINE_EXCEEDED,
                AiCallFailure.Outcome.UNKNOWN, null, workerRequestId = requestId))
        } catch (error: WorkerRequestPendingException) {
            withContext(NonCancellable) { requests.cancel(error.requestId) }
            throw AiCallException(AiCallFailure(AiCallFailure.Kind.DEADLINE_EXCEEDED,
                AiCallFailure.Outcome.UNKNOWN, null, workerRequestId = error.requestId))
        } catch (error: WorkerRequestResultUnavailableException) {
            throw AiCallException(AiCallFailure(AiCallFailure.Kind.WORKER_FAILURE,
                AiCallFailure.Outcome.UNKNOWN, null, workerRequestId = error.requestId))
        }
    }

    private suspend fun execute(
        target: ConversationRuntimeWorkerIdentity,
        payload: ByteArray,
        diagnosticId: String? = null,
    ): ByteArray =
        requests.execute(
            workerId = target.workerId,
            operation = WorkerGatewayOperation.AI_REQUEST_RESPONSE,
            payload = payload,
            policy = com.gromozeka.domain.service.WorkerRequestPolicy(executionTimeoutMillis = timeout.toMillis()),
            diagnosticId = diagnosticId,
        )
}
