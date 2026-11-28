package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.service.LiveTranscriptionEvent
import com.gromozeka.domain.service.LiveTranscriptionException
import com.gromozeka.domain.service.LiveTranscriptionTurnDetection
import com.gromozeka.infrastructure.ai.runtime.TestAiAdmission
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiLiveTranscriptionSemanticVadTest {
    @Test
    fun `native VAD owns automatic commits and Stop tolerates an empty final flush`() = runBlocking {
        withFixture(handler = {
            assertEquals("input_audio_buffer.append", receiveJson()["type"]!!.jsonPrimitive.content)
            sendSpeech("a", stopped = true)
            sendCommit("a", null)
            sendFinal("a", "A complete thought")
            disableVad()
            rejectEmptyStopFlush()
            expectCloseWithoutCommit()
        }) { session ->
            assertEquals(LiveTranscriptionTurnDetection.PROVIDER, session.turnDetection)
            coroutineScope {
                val final = CompletableDeferred<Unit>()
                val observed = mutableListOf<LiveTranscriptionEvent>()
                val collector = launch {
                    session.events.collect { event ->
                        observed += event
                        if (event is LiveTranscriptionEvent.Transcript && event.isFinal) final.complete(Unit)
                    }
                }
                session.appendAudio(ByteArray(3_200))
                final.await()
                assertFailsWith<IllegalStateException> { session.commit() }
                session.finishInput()
                collector.join()
                assertEquals(listOf(
                    LiveTranscriptionEvent.Committed("a", null),
                    LiveTranscriptionEvent.Transcript("a", "A complete thought", true),
                ), observed)
            }
        }
    }

    @Test
    fun `Stop flushes buffered audio even when VAD never reported speech`() = runBlocking {
        withFixture(handler = {
            receiveJson()
            disableVad()
            receiveStopFlush()
            sendCommit("quiet", null)
            sendFinal("quiet", "A quiet final phrase")
            expectCloseWithoutCommit()
        }) { session ->
            coroutineScope {
                val observed = mutableListOf<LiveTranscriptionEvent>()
                val collector = launch { session.events.collect { observed += it } }
                session.appendAudio(ByteArray(3_200))
                session.finishInput()
                collector.join()
                assertEquals(listOf(
                    LiveTranscriptionEvent.Committed("quiet", null),
                    LiveTranscriptionEvent.Transcript("quiet", "A quiet final phrase", true),
                ), observed)
            }
        }
    }

    @Test
    fun `late automatic acknowledgement cannot consume Stop flush and drain waits for both finals`() = runBlocking {
        val secondFinal = CompletableDeferred<Unit>()
        val releaseFirstFinal = CompletableDeferred<Unit>()
        withFixture(handler = {
            receiveJson()
            sendSpeech("a", stopped = true)
            sendSpeech("b", stopped = false)
            disableVad()
            sendCommit("a", null)
            receiveStopFlush()
            sendCommit("b", "a")
            sendFinal("b", "Second")
            releaseFirstFinal.await()
            sendFinal("a", "First")
            expectCloseWithoutCommit()
        }) { session ->
            coroutineScope {
                val observed = mutableListOf<LiveTranscriptionEvent>()
                val collector = launch {
                    session.events.collect { event ->
                        observed += event
                        if (event is LiveTranscriptionEvent.Transcript && event.itemId == "b") secondFinal.complete(Unit)
                    }
                }
                session.appendAudio(ByteArray(3_200))
                val stopping = async { session.finishInput() }
                secondFinal.await()
                assertFalse(stopping.isCompleted)
                releaseFirstFinal.complete(Unit)
                stopping.await()
                collector.join()
                assertEquals(setOf("a", "b"), observed.filterIsInstance<LiveTranscriptionEvent.Transcript>().map { it.itemId }.toSet())
                assertEquals(2, observed.filterIsInstance<LiveTranscriptionEvent.Committed>().size)
            }
        }
    }

    @Test
    fun `empty Stop flush still waits for an automatic turn already in flight`() = runBlocking {
        val emptyRejected = CompletableDeferred<Unit>()
        val releaseAutomaticTurn = CompletableDeferred<Unit>()
        withFixture(handler = {
            receiveJson()
            sendSpeech("late", stopped = false)
            disableVad()
            rejectEmptyStopFlush()
            emptyRejected.complete(Unit)
            releaseAutomaticTurn.await()
            send("""{"type":"input_audio_buffer.speech_stopped","item_id":"late"}""")
            sendCommit("late", null)
            sendFinal("late", "Late automatic final")
            expectCloseWithoutCommit()
        }) { session ->
            coroutineScope {
                val collector = launch { session.events.collect { } }
                session.appendAudio(ByteArray(3_200))
                val stopping = async { session.finishInput() }
                emptyRejected.await()
                assertFalse(stopping.isCompleted)
                releaseAutomaticTurn.complete(Unit)
                stopping.await()
                collector.join()
            }
        }
    }

    @Test
    fun `Stop without audio treats the exact empty flush error as a successful drain`() = runBlocking {
        withFixture(handler = {
            disableVad()
            rejectEmptyStopFlush()
            expectCloseWithoutCommit()
        }) { session ->
            session.finishInput()
            assertFailsWith<IllegalStateException> { session.appendAudio(ByteArray(3_200)) }
            assertFailsWith<IllegalStateException> { session.finishInput() }
        }
    }

    @Test
    fun `empty commit error for another event is not hidden by Stop`() = runBlocking {
        withFixture(handler = {
            disableVad()
            receiveStopFlush()
            send("""{"type":"error","error":{"type":"invalid_request_error","code":"input_audio_buffer_commit_empty","message":"Wrong event","event_id":"another-event"}}""")
        }, expectedOutcome = AiAdmissionOutcome.FAILED) { session ->
            assertFalse(assertFailsWith<LiveTranscriptionException> { session.finishInput() }.retryable)
        }
    }

    @Test
    fun `provider failure while disabling VAD wakes Stop immediately`() = runBlocking {
        withFixture(handler = {
            assertEquals("session.update", receiveJson()["type"]!!.jsonPrimitive.content)
            send("""{"type":"error","error":{"code":"server_error","message":"synthetic failure"}}""")
        }, expectedOutcome = AiAdmissionOutcome.FAILED) { session ->
            withTimeout(1_000) {
                assertTrue(assertFailsWith<LiveTranscriptionException> { session.finishInput() }.retryable)
            }
        }
    }

    @Test
    fun `Stop rejects a configuration acknowledgement that leaves VAD enabled`() = runBlocking {
        withFixture(handler = {
            receiveJson()
            send("""{"type":"session.updated","session":{"audio":{"input":{"turn_detection":{"type":"semantic_vad","eagerness":"low"}}}}}""")
        }, expectedOutcome = AiAdmissionOutcome.FAILED) { session ->
            assertFailsWith<IllegalStateException> { session.finishInput() }
        }
    }

    private suspend fun DefaultWebSocketServerSession.receiveJson(): JsonObject =
        Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject

    private suspend fun DefaultWebSocketServerSession.sendSpeech(id: String, stopped: Boolean) {
        send("""{"type":"input_audio_buffer.speech_started","item_id":"$id"}""")
        if (stopped) send("""{"type":"input_audio_buffer.speech_stopped","item_id":"$id"}""")
    }

    private suspend fun DefaultWebSocketServerSession.sendCommit(id: String, previous: String?) {
        val predecessor = previous?.let { "\"$it\"" } ?: "null"
        send("""{"type":"input_audio_buffer.committed","item_id":"$id","previous_item_id":$predecessor}""")
    }

    private suspend fun DefaultWebSocketServerSession.sendFinal(id: String, text: String) {
        send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"$id","transcript":"$text"}""")
    }

    private suspend fun DefaultWebSocketServerSession.disableVad() {
        val update = receiveJson()
        assertEquals("session.update", update["type"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, update["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject["turn_detection"])
        send("""{"type":"session.updated","session":{"audio":{"input":{"turn_detection":null}}}}""")
    }

    private suspend fun DefaultWebSocketServerSession.receiveStopFlush(): String {
        val commit = receiveJson()
        assertEquals("input_audio_buffer.commit", commit["type"]!!.jsonPrimitive.content)
        return commit["event_id"]!!.jsonPrimitive.content.also {
            assertTrue(it.startsWith("gromozeka-stop-flush-"))
        }
    }

    private suspend fun DefaultWebSocketServerSession.rejectEmptyStopFlush() {
        val eventId = receiveStopFlush()
        send("""{"type":"error","error":{"type":"invalid_request_error","code":"input_audio_buffer_commit_empty","message":"Buffer is empty","event_id":"$eventId"}}""")
    }

    private suspend fun DefaultWebSocketServerSession.expectCloseWithoutCommit() {
        for (frame in incoming) {
            if (frame is Frame.Text) error("Unexpected client event after Live drain: ${Json.parseToJsonElement(frame.readText()).jsonObject["type"]}")
        }
    }

    private suspend fun withFixture(
        handler: suspend DefaultWebSocketServerSession.() -> Unit,
        expectedOutcome: AiAdmissionOutcome = AiAdmissionOutcome.SUCCEEDED,
        body: suspend (OpenAiLiveTranscriptionSession) -> Unit,
    ) {
        val handled = CompletableDeferred<Unit>()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/v1/realtime") {
                    try {
                        val input = receiveJson()["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
                        assertEquals("gpt-transcribe", input["transcription"]!!.jsonObject["model"]!!.jsonPrimitive.content)
                        assertEquals("semantic_vad", input["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                        assertEquals("low", input["turn_detection"]!!.jsonObject["eagerness"]!!.jsonPrimitive.content)
                        send("""{"type":"session.updated"}""")
                        handler()
                        handled.complete(Unit)
                    } catch (error: Throwable) {
                        handled.completeExceptionally(error)
                        throw error
                    }
                }
            }
        }.start(wait = false)
        val admission = TestAiAdmission()
        val model = AiModelConfiguration(
            id = AiModelConfiguration.Id("semantic-live-test"),
            connectionId = AiConnection.Id("fixture"),
            providerModelId = "gpt-transcribe",
            displayName = "Synthetic VAD fixture",
        )
        try {
            val port = server.engine.resolvedConnectors().single().port
            val session = OpenAiLiveTranscriptionSession(
                OpenAiLiveTranscriptionConfiguration("http://127.0.0.1:$port/v1", "synthetic-test-key", model.providerModelId, listOf("he", "en", "ru")),
                "synthetic-user", admission.openSession(model, "LIVE_SEMANTIC_VAD_TEST"),
            )
            try {
                withTimeout(10_000) { session.start(); body(session) }
            } finally { session.close(expectedOutcome) }
            withTimeout(3_000) { handled.await() }
            assertTrue(admission.active.isEmpty())
            assertEquals(expectedOutcome, admission.completed.single().second)
        } finally { server.stop(0, 1_000) }
    }
}
