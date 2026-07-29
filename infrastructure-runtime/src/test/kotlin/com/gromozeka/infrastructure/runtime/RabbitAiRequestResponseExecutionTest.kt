package com.gromozeka.infrastructure.runtime

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiModelConfiguration
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
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.runBlocking
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RabbitAiRequestResponseExecutionTest {
    @Test
    fun `rabbit round trip preserves every finite AI operation`() = runBlocking {
        if (System.getenv("GROMOZEKA_RABBIT_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val connectionFactory = CachingConnectionFactory("localhost", 5672)
        val exchangeName = "gromozeka.ai.request-response.test.${uuid7()}"
        val topology = RabbitAiRequestResponseTopology(
            connectionFactory = connectionFactory,
            exchangeName = exchangeName,
            queuePrefix = "$exchangeName.queue",
        )
        val identity = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId("ai-worker"),
            sessionId = ConversationRuntimeWorkerSessionId("ai-worker-session"),
        )
        val handler = TestHandler()
        val consumer = RabbitAiRequestResponseExecutionConsumer(
            connectionFactory = connectionFactory,
            rabbitTemplate = RabbitTemplate(connectionFactory),
            topology = topology,
            workerIdentity = identity,
            handler = handler,
            maxMessageBytes = 1024 * 1024,
        )
        val client = RabbitAiRequestResponseExecutionClient(
            connectionFactory = connectionFactory,
            topology = topology,
            timeoutMillis = 5_000,
            maxMessageBytes = 1024 * 1024,
        )

        try {
            consumer.start()

            assertEquals(
                handler.runtimeResponse,
                client.call(identity, handler.selection, null, handler.runtimeRequest),
            )
            assertEquals(
                handler.embeddingResponse,
                client.embed(identity, handler.embeddingRequest),
            )
            assertEquals(
                handler.transcript,
                client.transcribe(identity, handler.transcriptionRequest),
            )
            val synthesis = client.synthesize(identity, handler.synthesisRequest)
            assertContentEquals(handler.synthesisResponse.audioData, synthesis.audioData)
            assertEquals(handler.synthesisResponse.mediaType, synthesis.mediaType)
            assertEquals(handler.synthesisResponse.fileExtension, synthesis.fileExtension)
        } finally {
            consumer.stop()
            RabbitAdmin(connectionFactory).deleteExchange(exchangeName)
            connectionFactory.destroy()
        }
    }

    private class TestHandler : AiRequestResponseExecutionHandler {
        val selection = AiRuntimeSelection(AiModelConfiguration.Id("model-config"))
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
            selection: AiRuntimeSelection,
            workspaceRootPath: String?,
            request: AiRuntimeRequest,
        ): AiRuntimeResponse {
            require(selection == this.selection)
            require(workspaceRootPath == null)
            require(request == runtimeRequest)
            return runtimeResponse
        }

        override suspend fun embed(request: AiEmbeddingRequest): AiEmbeddingResponse {
            require(request == embeddingRequest)
            return embeddingResponse
        }

        override suspend fun transcribe(request: AiSpeechTranscriptionRequest): String {
            require(request.format == transcriptionRequest.format)
            require(request.engine == transcriptionRequest.engine)
            require(request.selection == transcriptionRequest.selection)
            require(request.language == transcriptionRequest.language)
            require(request.prompt == transcriptionRequest.prompt)
            require(request.audioData.contentEquals(transcriptionRequest.audioData))
            return transcript
        }

        override suspend fun synthesize(
            request: AiSpeechSynthesisRequest,
        ): AiSpeechSynthesisResponse {
            require(request == synthesisRequest)
            return synthesisResponse
        }
    }
}
