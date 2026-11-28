package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.ai.AiAdmissionLease
import com.gromozeka.domain.model.ai.AiAdmissionOperation
import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiConcurrencyGroup
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.service.AiCallAdmission
import com.gromozeka.domain.service.LiveTranscriptionEvent
import com.gromozeka.domain.service.LiveTranscriptionException
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class OpenAiLiveTranscriptionSocketTest {
    @Test
    fun `real WebSocket carries configuration PCM partial and final without a voice response`() = runBlocking {
        val sentConfiguration = CompletableDeferred<JsonObject>()
        val sentAudio = CompletableDeferred<ByteArray>()
        withSocket(handler = {
            assertEquals("transcription", call.request.queryParameters["intent"])
            assertEquals(null, call.request.queryParameters["model"])
            sentConfiguration.complete(receiveJson())
            send("""{"type":"session.updated"}""")
            val audio = receiveJson()
            assertEquals("input_audio_buffer.append", audio["type"]!!.jsonPrimitive.content)
            sentAudio.complete(Base64.getDecoder().decode(audio["audio"]!!.jsonPrimitive.content))
            send("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"a","delta":"שלום "}""")
            assertEquals("input_audio_buffer.commit", receiveJson()["type"]!!.jsonPrimitive.content)
            send("""{"type":"input_audio_buffer.committed","item_id":"a","previous_item_id":null}""")
            send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"a","transcript":"שלום world"}""")
            for (frame in incoming) { /* Wait for the owned client connection to close. */ }
        }) { baseUrl ->
            val admission = RecordingAdmission()
            val session = session(baseUrl, admission)
            try {
                withTimeout(10_000) {
                    session.start()
                    val input = sentConfiguration.await()["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
                    assertEquals(JsonNull, input["turn_detection"])
                    assertEquals("gpt-live-transcribe", input["transcription"]!!.jsonObject["model"]!!.jsonPrimitive.content)
                    assertFalse("language" in input["transcription"]!!.jsonObject)
                    coroutineScope {
                        val partial = CompletableDeferred<LiveTranscriptionEvent.Transcript>()
                        val observed = mutableListOf<LiveTranscriptionEvent>()
                        val collector = launch {
                            session.events.collect { event ->
                                observed += event
                                if (event is LiveTranscriptionEvent.Transcript && !event.isFinal) partial.complete(event)
                            }
                        }
                        val pcm = ByteArray(3_200) { (it % 127).toByte() }
                        session.appendAudio(pcm)
                        assertEquals("שלום ", partial.await().text)
                        assertContentEquals(resamplePcm16To24kLittleEndian(pcm, 16_000, OpenAiRealtimePcmByteOrder.BIG_ENDIAN), sentAudio.await())
                        session.commit()
                        session.finishInput()
                        collector.join()
                        assertEquals(3, observed.size)
                        assertEquals(LiveTranscriptionEvent.Committed("a", null), observed[1])
                        assertEquals(LiveTranscriptionEvent.Transcript("a", "שלום world", true), observed[2])
                    }
                }
            } finally {
                session.close(AiAdmissionOutcome.SUCCEEDED)
                session.close(AiAdmissionOutcome.SUCCEEDED)
            }
            assertEquals(listOf(AiAdmissionOutcome.SUCCEEDED), admission.outcomes)
        }
    }

    @Test
    fun `network close reports retryable failure instead of inventing a final`() = runBlocking {
        val disconnect = CompletableDeferred<Unit>()
        withSocket(handler = {
            receiveJson()
            send("""{"type":"session.updated"}""")
            receiveJson()
            send("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"a","delta":"unfinished"}""")
            disconnect.await()
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "synthetic disconnect"))
        }) { baseUrl ->
            val admission = RecordingAdmission()
            val session = session(baseUrl, admission)
            try {
                withTimeout(10_000) {
                    session.start()
                    supervisorScope {
                        val partial = CompletableDeferred<Unit>()
                        val observed = mutableListOf<LiveTranscriptionEvent>()
                        val collector = async {
                            session.events.collect { event -> observed += event; partial.complete(Unit) }
                        }
                        session.appendAudio(ByteArray(3_200))
                        partial.await()
                        disconnect.complete(Unit)
                        assertTrue(assertFailsWith<LiveTranscriptionException> { collector.await() }.retryable)
                        assertTrue(observed.filterIsInstance<LiveTranscriptionEvent.Transcript>().none { it.isFinal })
                    }
                }
            } finally {
                session.close(AiAdmissionOutcome.FAILED)
            }
            assertEquals(listOf(AiAdmissionOutcome.FAILED), admission.outcomes)
        }
    }

    @Test
    fun `non-string final transcript is rejected instead of becoming invented original text`() = runBlocking {
        withSocket(handler = {
            receiveJson()
            send("""{"type":"session.updated"}""")
            receiveJson()
            receiveJson()
            send("""{"type":"input_audio_buffer.committed","item_id":"a","previous_item_id":null}""")
            send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"a","transcript":123}""")
            for (frame in incoming) { }
        }) { baseUrl ->
            val admission = RecordingAdmission()
            val session = session(baseUrl, admission)
            try {
                withTimeout(10_000) {
                    session.start()
                    supervisorScope {
                        val reader = async { session.events.collect { } }
                        session.appendAudio(ByteArray(3_200))
                        session.commit()
                        assertFailsWith<IllegalArgumentException> { reader.await() }
                    }
                }
            } finally {
                session.close(AiAdmissionOutcome.FAILED)
            }
        }
    }

    @Test
    fun `provider error interrupts a pending drain without waiting for its deadline`() = runBlocking {
        withSocket(handler = {
            receiveJson()
            send("""{"type":"session.updated"}""")
            receiveJson()
            receiveJson()
            send("""{"type":"error","error":{"code":"server_error","message":"synthetic failure"}}""")
            for (frame in incoming) { }
        }) { baseUrl ->
            val session = session(baseUrl, RecordingAdmission())
            try {
                session.start()
                session.appendAudio(ByteArray(3_200))
                session.commit()
                withTimeout(1_000) {
                    assertTrue(assertFailsWith<LiveTranscriptionException> { session.finishInput() }.retryable)
                }
            } finally {
                session.close(AiAdmissionOutcome.FAILED)
            }
        }
    }

    private suspend fun session(baseUrl: String, admission: RecordingAdmission): OpenAiLiveTranscriptionSession {
        val model = AiModelConfiguration(
            id = AiModelConfiguration.Id("live-test"),
            connectionId = AiConnection.Id("openai-test"),
            providerModelId = "gpt-live-transcribe",
            displayName = "Synthetic fixture",
        )
        return OpenAiLiveTranscriptionSession(
            OpenAiLiveTranscriptionConfiguration(baseUrl, "synthetic-test-key", model.providerModelId, listOf("he", "en", "ru")),
            "synthetic-user",
            admission.openSession(model, "LIVE_TRANSCRIPTION_TEST"),
        )
    }

    private suspend fun DefaultWebSocketServerSession.receiveJson(): JsonObject =
        Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject

    private suspend fun withSocket(
        handler: suspend DefaultWebSocketServerSession.() -> Unit,
        body: suspend (String) -> Unit,
    ) {
        val completed = CompletableDeferred<Unit>()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing { webSocket("/v1/realtime") {
                try { handler(); completed.complete(Unit) }
                catch (error: Throwable) { completed.completeExceptionally(error); throw error }
            } }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            body("http://127.0.0.1:$port/v1")
            withTimeout(2_000) { completed.await() }
        } finally {
            server.stop(0, 1_000)
        }
    }

    private class RecordingAdmission : AiCallAdmission() {
        val outcomes = mutableListOf<AiAdmissionOutcome>()
        override suspend fun acquire(operation: AiAdmissionOperation): AiAdmissionLease =
            AiAdmissionLease(operation, AiConcurrencyGroup.Id("fixture"), Clock.System.now())
        override suspend fun finish(lease: AiAdmissionLease, outcome: AiAdmissionOutcome) { outcomes += outcome }
    }
}
