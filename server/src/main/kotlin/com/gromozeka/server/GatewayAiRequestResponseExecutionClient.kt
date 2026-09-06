package com.gromozeka.server

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
        val tracedRequest = request.copy(options = request.options.copy(
            toolContext = request.options.toolContext + ("aiCallDiagnosticId" to diagnosticId),
        ))
        val payload = AiRequestResponseGatewayCodec.encodeCallRequest(runtime, workspaceRootPath, tracedRequest)
        log.debug {
            "AI_GATEWAY_TRACE call=$diagnosticId phase=encoded worker=${target.workerId.value} " +
                "provider=${runtime.connection.kind} bytes=${payload.size} " +
                "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}"
        }
        return try {
            val response = execute(target, payload, diagnosticId)
            log.debug {
                "AI_GATEWAY_TRACE call=$diagnosticId phase=response_received bytes=${response.size} " +
                    "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}"
            }
            AiRequestResponseGatewayCodec.decodeCallResponse(response)
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
