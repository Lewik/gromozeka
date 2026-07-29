package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiRequestResponseExecutionHandler
import com.gromozeka.domain.service.AiSpeechSynthesisRequest
import com.gromozeka.domain.service.AiSpeechSynthesisResponse
import com.gromozeka.domain.service.AiSpeechTranscriptionRequest
import com.gromozeka.domain.service.DirectAiEmbeddingProvider
import com.gromozeka.domain.service.DirectAiRuntimeProvider
import com.gromozeka.domain.service.DirectAiSpeechToTextProvider
import com.gromozeka.domain.service.DirectAiTextToSpeechProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class DirectAiRequestResponseExecutionHandler(
    private val runtimeProvider: DirectAiRuntimeProvider,
    private val embeddingProvider: DirectAiEmbeddingProvider,
    private val speechToTextProvider: DirectAiSpeechToTextProvider,
    private val textToSpeechProvider: DirectAiTextToSpeechProvider,
) : AiRequestResponseExecutionHandler {
    override suspend fun call(
        selection: AiRuntimeSelection,
        workspaceRootPath: String?,
        request: AiRuntimeRequest,
    ): AiRuntimeResponse =
        runtimeProvider.getRuntime(selection, workspaceRootPath).call(request)

    override suspend fun embed(request: AiEmbeddingRequest): AiEmbeddingResponse =
        embeddingProvider.embed(request)

    override suspend fun transcribe(request: AiSpeechTranscriptionRequest): String =
        speechToTextProvider.transcribe(request)

    override suspend fun synthesize(request: AiSpeechSynthesisRequest): AiSpeechSynthesisResponse =
        textToSpeechProvider.synthesize(request)
}
