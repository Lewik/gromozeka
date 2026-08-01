package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiEmbeddingVector
import com.gromozeka.domain.service.AiRequestResponseExecutionHandler
import com.gromozeka.domain.service.AiSpeechSynthesisRequest
import com.gromozeka.domain.service.AiSpeechSynthesisResponse
import com.gromozeka.domain.service.AiSpeechTranscriptionRequest
import com.gromozeka.domain.service.ResolvedAiRuntime
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AiRequestResponseGatewayCodecTest {
    @Test
    fun `codec preserves resolved configuration for every finite AI operation`() = runBlocking {
        val handler = TestHandler()

        val callPayload = AiRequestResponseGatewayCodec.execute(
            AiRequestResponseGatewayCodec.encodeCallRequest(
                handler.runtime,
                null,
                handler.runtimeRequest,
            ),
            handler,
        )
        assertEquals(handler.runtimeResponse, AiRequestResponseGatewayCodec.decodeCallResponse(callPayload))

        val embeddingPayload = AiRequestResponseGatewayCodec.execute(
            AiRequestResponseGatewayCodec.encodeEmbeddingRequest(
                handler.runtime,
                handler.modelSpec,
                handler.embeddingRequest,
            ),
            handler,
        )
        assertEquals(
            handler.embeddingResponse,
            AiRequestResponseGatewayCodec.decodeEmbeddingResponse(embeddingPayload),
        )

        val transcriptionPayload = AiRequestResponseGatewayCodec.execute(
            AiRequestResponseGatewayCodec.encodeTranscriptionRequest(
                handler.runtime,
                null,
                handler.transcriptionRequest,
            ),
            handler,
        )
        assertEquals(
            handler.transcript,
            AiRequestResponseGatewayCodec.decodeTranscriptionResponse(transcriptionPayload),
        )

        val synthesisPayload = AiRequestResponseGatewayCodec.execute(
            AiRequestResponseGatewayCodec.encodeSynthesisRequest(
                handler.runtime,
                handler.synthesisRequest,
            ),
            handler,
        )
        val synthesis = AiRequestResponseGatewayCodec.decodeSynthesisResponse(synthesisPayload)
        assertContentEquals(handler.synthesisResponse.audioData, synthesis.audioData)
        assertEquals(handler.synthesisResponse.mediaType, synthesis.mediaType)
        assertEquals(handler.synthesisResponse.fileExtension, synthesis.fileExtension)
    }

    @Test
    fun `codec preserves Claude Code voice connection for Worker transcription`() = runBlocking {
        val connection = AiConnection.ClaudeCode(
            id = AiConnection.Id("claude-voice"),
            displayName = "Claude voice",
            executablePath = "/opt/claude/bin/claude",
            voiceTranscriptionEnabled = true,
        )
        val request = AiSpeechTranscriptionRequest(
            audioData = byteArrayOf(7, 8, 9),
            format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
            engine = UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE,
            selection = null,
            claudeCodeConnection = connection,
            language = "en",
            prompt = null,
        )
        val handler = object : AiRequestResponseExecutionHandler by TestHandler() {
            override suspend fun transcribe(
                runtime: ResolvedAiRuntime?,
                localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
                request: AiSpeechTranscriptionRequest,
            ): String {
                require(runtime == null)
                require(localWhisperSettings == null)
                require(request.claudeCodeConnection == connection)
                require(request.audioData.contentEquals(byteArrayOf(7, 8, 9)))
                return "voice transcript"
            }
        }

        val payload = AiRequestResponseGatewayCodec.execute(
            AiRequestResponseGatewayCodec.encodeTranscriptionRequest(null, null, request),
            handler,
        )

        assertEquals("voice transcript", AiRequestResponseGatewayCodec.decodeTranscriptionResponse(payload))
    }

    private class TestHandler : AiRequestResponseExecutionHandler {
        private val selection = AiRuntimeSelection(AiModelConfiguration.Id("model-config"))
        val runtime = ResolvedAiRuntime(
            connection = AiConnection.OpenAiApi(
                id = AiConnection.Id("connection"),
                displayName = "Connection",
                enabled = true,
            ),
            modelConfiguration = AiModelConfiguration(
                id = selection.modelConfigurationId,
                connectionId = AiConnection.Id("connection"),
                providerModelId = "test-model",
                displayName = "Test model",
            ),
        )
        val modelSpec = AiModelSpec(
            id = runtime.modelConfiguration.providerModelId,
            provider = AiProvider.OPENAI,
            capabilities = setOf(AiModelCapability.EMBEDDINGS),
            limits = AiModelSpec.Limits(
                embeddings = AiModelSpec.Limits.Embeddings(dimensions = 2),
            ),
        )
        val runtimeRequest = AiRuntimeRequest(
            systemPrompts = listOf("system"),
            messages = emptyList(),
        )
        val runtimeResponse = AiRuntimeResponse(
            messages = emptyList(),
            finishReason = "test",
        )
        val embeddingRequest = AiEmbeddingRequest(
            selection = selection,
            inputs = listOf("one", "two"),
        )
        val embeddingResponse = AiEmbeddingResponse(
            modelId = "embedding-model",
            dimensions = 2,
            vectors = listOf(
                AiEmbeddingVector(0, listOf(0.1f, 0.2f)),
                AiEmbeddingVector(1, listOf(0.3f, 0.4f)),
            ),
        )
        val transcriptionRequest = AiSpeechTranscriptionRequest(
            audioData = byteArrayOf(1, 2, 3),
            format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
            engine = UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API,
            selection = selection,
            language = "en",
            prompt = "Names",
        )
        val transcript = "transcribed"
        val synthesisRequest = AiSpeechSynthesisRequest(
            selection = selection,
            text = "Hello",
            voiceTone = "neutral",
            voice = "marin",
            speed = 1.0f,
        )
        val synthesisResponse = AiSpeechSynthesisResponse(
            audioData = byteArrayOf(4, 5, 6),
            mediaType = "audio/wav",
            fileExtension = "wav",
        )

        override suspend fun call(
            runtime: ResolvedAiRuntime,
            workspaceRootPath: String?,
            request: AiRuntimeRequest,
        ): AiRuntimeResponse {
            require(runtime == this.runtime)
            require(workspaceRootPath == null)
            require(request == runtimeRequest)
            return runtimeResponse
        }

        override suspend fun embed(
            runtime: ResolvedAiRuntime,
            modelSpec: AiModelSpec,
            request: AiEmbeddingRequest,
        ): AiEmbeddingResponse {
            require(runtime == this.runtime)
            require(modelSpec == this.modelSpec)
            require(request == embeddingRequest)
            return embeddingResponse
        }

        override suspend fun transcribe(
            runtime: ResolvedAiRuntime?,
            localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
            request: AiSpeechTranscriptionRequest,
        ): String {
            require(runtime == this.runtime)
            require(localWhisperSettings == null)
            require(request.format == transcriptionRequest.format)
            require(request.engine == transcriptionRequest.engine)
            require(request.selection == transcriptionRequest.selection)
            require(request.language == transcriptionRequest.language)
            require(request.prompt == transcriptionRequest.prompt)
            require(request.audioData.contentEquals(transcriptionRequest.audioData))
            return transcript
        }

        override suspend fun synthesize(
            runtime: ResolvedAiRuntime,
            request: AiSpeechSynthesisRequest,
        ): AiSpeechSynthesisResponse {
            require(runtime == this.runtime)
            require(request == synthesisRequest)
            return synthesisResponse
        }
    }
}
