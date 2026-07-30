package com.gromozeka.server

import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.AiSpeechSynthesisRequest
import com.gromozeka.domain.service.AiSpeechSynthesisResponse
import com.gromozeka.domain.service.AiSpeechTranscriptionRequest
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.infrastructure.runtime.AiRequestResponseGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.time.Duration

@Service
@Primary
class GatewayAiRequestResponseExecutionClient(
    private val sessionRegistry: WorkerGatewaySessionRegistry,
    @Value("\${gromozeka.runtime.ai-request-response.timeout-millis:1800000}")
    timeoutMillis: Long,
) : AiRequestResponseExecutionClient {
    private val timeout = Duration.ofMillis(timeoutMillis)

    init {
        require(timeoutMillis > 0) { "AI request-response timeout must be positive" }
    }

    override suspend fun call(
        target: ConversationRuntimeWorkerIdentity,
        selection: AiRuntimeSelection,
        workspaceRootPath: String?,
        request: AiRuntimeRequest,
    ): AiRuntimeResponse =
        execute(
            target,
            AiRequestResponseGatewayCodec.encodeCallRequest(selection, workspaceRootPath, request),
        ).let(AiRequestResponseGatewayCodec::decodeCallResponse)

    override suspend fun embed(
        target: ConversationRuntimeWorkerIdentity,
        request: AiEmbeddingRequest,
    ): AiEmbeddingResponse =
        execute(target, AiRequestResponseGatewayCodec.encodeEmbeddingRequest(request))
            .let(AiRequestResponseGatewayCodec::decodeEmbeddingResponse)

    override suspend fun transcribe(
        target: ConversationRuntimeWorkerIdentity,
        request: AiSpeechTranscriptionRequest,
    ): String =
        execute(target, AiRequestResponseGatewayCodec.encodeTranscriptionRequest(request))
            .let(AiRequestResponseGatewayCodec::decodeTranscriptionResponse)

    override suspend fun synthesize(
        target: ConversationRuntimeWorkerIdentity,
        request: AiSpeechSynthesisRequest,
    ): AiSpeechSynthesisResponse =
        execute(target, AiRequestResponseGatewayCodec.encodeSynthesisRequest(request))
            .let(AiRequestResponseGatewayCodec::decodeSynthesisResponse)

    private suspend fun execute(
        target: ConversationRuntimeWorkerIdentity,
        payload: ByteArray,
    ): ByteArray =
        sessionRegistry.execute(
            target = target,
            operation = WorkerGatewayOperation.AI_REQUEST_RESPONSE,
            payload = payload,
            timeout = timeout,
        )
}
