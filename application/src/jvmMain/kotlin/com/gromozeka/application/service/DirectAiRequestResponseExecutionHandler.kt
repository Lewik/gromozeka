package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.UserProfile
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
import com.gromozeka.domain.service.ResolvedAiRuntime
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
        runtime: ResolvedAiRuntime,
        workspaceRootPath: String?,
        request: AiRuntimeRequest,
    ): AiRuntimeResponse =
        runtimeProvider.getRuntime(runtime, workspaceRootPath).call(request)

    override suspend fun embed(
        runtime: ResolvedAiRuntime,
        request: AiEmbeddingRequest,
    ): AiEmbeddingResponse =
        embeddingProvider.embed(runtime, request)

    override suspend fun transcribe(
        runtime: ResolvedAiRuntime?,
        localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
        request: AiSpeechTranscriptionRequest,
    ): String =
        speechToTextProvider.transcribe(runtime, localWhisperSettings, request)

    override suspend fun synthesize(
        runtime: ResolvedAiRuntime,
        request: AiSpeechSynthesisRequest,
    ): AiSpeechSynthesisResponse =
        textToSpeechProvider.synthesize(runtime, request)
}
