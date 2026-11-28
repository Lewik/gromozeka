package com.gromozeka.presentation.services

import com.gromozeka.client.LiveInterpreterClient
import com.gromozeka.client.LiveInterpreterRemoteSession
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.LiveTranscriptionException
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.remote.protocol.LiveInterpreterNativeTranslationEvent
import com.gromozeka.remote.protocol.LiveInterpreterStatusEvent
import com.gromozeka.remote.protocol.LiveInterpreterStoppedEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranscriptEvent
import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import com.gromozeka.remote.protocol.ServerPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveInterpreterControllerTest {
    private val selection = AiRuntimeSelection(AiModelConfiguration.Id("live-test"))

    @Test
    fun `default hints are editable and empty means auto`() = runTest {
        val client = FakeClient()
        val microphone = FakeMicrophone()
        val controller = LiveInterpreterController(client, microphone, backgroundScope)
        assertEquals(listOf("he", "en", "ru"), parseLiveLanguageHints(controller.state.value.languageHintsText))
        controller.setLanguages("")
        controller.start(selection)
        runCurrent()
        assertEquals(emptyList(), client.languages.single())
        assertEquals(LiveInterpreterPhase.LISTENING, controller.state.value.phase)
        controller.stop()
        runCurrent()
        assertEquals(LiveInterpreterPhase.STOPPED, controller.state.value.phase)
    }

    @Test
    fun `partial is replaced by final without duplicating or filtering original language`() = runTest {
        val client = FakeClient()
        val controller = LiveInterpreterController(client, FakeMicrophone(), backgroundScope)
        controller.setLanguages("he, en")
        controller.start(selection)
        runCurrent()
        val remote = client.sessions.single()
        remote.transcript("a", null, "Прив", false)
        runCurrent()
        assertFalse(controller.state.value.segments.single().isFinal)
        remote.transcript("a", 0, "Привет world", true)
        runCurrent()
        assertEquals("Привет world", controller.state.value.segments.single().text)
        assertTrue(controller.state.value.segments.single().isFinal)
        controller.stop()
        runCurrent()
    }

    @Test
    fun `final turn order follows committed positions rather than arrival`() = runTest {
        val client = FakeClient()
        val controller = LiveInterpreterController(client, FakeMicrophone(), backgroundScope)
        controller.start(selection)
        runCurrent()
        client.sessions.single().transcript("second", 1, "second", true)
        client.sessions.single().transcript("first", 0, "first", true)
        runCurrent()
        assertEquals(listOf("first", "second"), controller.state.value.segments.map { it.text })
        controller.stop()
        runCurrent()
    }

    @Test
    fun `network recovery preserves final text and visibly interrupts a partial`() = runTest {
        val client = FakeClient()
        val mic = FakeMicrophone()
        val controller = LiveInterpreterController(client, mic, backgroundScope, listOf(100))
        controller.start(selection)
        runCurrent()
        val old = client.sessions.single()
        old.transcript("a", 0, "confirmed", true)
        old.transcript("b", null, "unfinished", false)
        runCurrent()
        old.output.close(LiveTranscriptionException("synthetic network failure", true))
        runCurrent()
        assertEquals(LiveInterpreterPhase.RECONNECTING, controller.state.value.phase)
        assertTrue(controller.state.value.entries.any { it is LiveTranscriptEntry.Gap })
        assertTrue(controller.state.value.segments.last().interrupted)
        assertTrue(mic.captures.single().cancelled)
        assertTrue(old.closed)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, client.sessions.size)
        assertEquals(LiveInterpreterPhase.LISTENING, controller.state.value.phase)
        assertEquals("confirmed", controller.state.value.segments.first().text)
        client.sessions.last().transcript("a", 0, "continued", true)
        runCurrent()
        assertEquals(listOf("confirmed", "unfinished", "continued"), controller.state.value.segments.map { it.text })
        controller.stop()
        runCurrent()
    }

    @Test
    fun `non-network capture failure pauses the stage and manual resume keeps text`() = runTest {
        val client = FakeClient()
        val mic = FakeMicrophone()
        val controller = LiveInterpreterController(client, mic, backgroundScope)
        controller.start(selection)
        runCurrent()
        client.sessions.single().transcript("a", 0, "kept original", true)
        runCurrent()
        mic.captures.single().failure.completeExceptionally(IllegalStateException("synthetic capture failure"))
        runCurrent()
        assertEquals(LiveInterpreterPhase.PAUSED, controller.state.value.phase)
        assertEquals(1, client.sessions.size)
        assertEquals("kept original", controller.state.value.segments.single().text)
        controller.setLanguages("")
        controller.start(selection)
        runCurrent()
        assertEquals(LiveInterpreterPhase.LISTENING, controller.state.value.phase)
        assertEquals(emptyList(), client.languages.last())
        assertEquals("kept original", controller.state.value.segments.single().text)
        controller.stop()
        runCurrent()
    }

    @Test
    fun `contract violation does not overwrite a final and does not auto retry`() = runTest {
        val client = FakeClient()
        val controller = LiveInterpreterController(client, FakeMicrophone(), backgroundScope)
        controller.start(selection)
        runCurrent()
        val remote = client.sessions.single()
        remote.transcript("a", 0, "original", true)
        runCurrent()
        remote.transcript("a", 0, "invalid rewrite", false)
        runCurrent()
        assertEquals(LiveInterpreterPhase.PAUSED, controller.state.value.phase)
        assertEquals("original", controller.state.value.segments.single().text)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, client.sessions.size)
    }

    @Test
    fun `stop during reconnect prevents any later automatic restart`() = runTest {
        val client = FakeClient()
        val controller = LiveInterpreterController(client, FakeMicrophone(), backgroundScope, listOf(100))
        controller.start(selection)
        runCurrent()
        client.sessions.single().output.close(LiveTranscriptionException("network", true))
        runCurrent()
        controller.stop()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(LiveInterpreterPhase.STOPPED, controller.state.value.phase)
        assertEquals(1, client.sessions.size)
    }

    @Test
    fun `retry attempts are bounded when startup keeps failing`() = runTest {
        val client = FakeClient().apply { startFailure = LiveTranscriptionException("network", true) }
        val microphone = FakeMicrophone()
        val controller = LiveInterpreterController(client, microphone, backgroundScope, listOf(10, 20))
        controller.start(selection)
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        advanceTimeBy(20)
        runCurrent()
        assertEquals(3, client.starts)
        assertEquals(LiveInterpreterPhase.PAUSED, controller.state.value.phase)
        assertTrue(microphone.captures.isEmpty())
    }

    @Test
    fun `owner disposal releases microphone and remote session`() = runTest {
        val client = FakeClient()
        val microphone = FakeMicrophone()
        val controller = LiveInterpreterController(client, microphone, backgroundScope)
        controller.start(selection)
        runCurrent()
        controller.cancel()
        runCurrent()
        assertTrue(microphone.captures.single().cancelled)
        assertTrue(client.sessions.single().closed)
        assertEquals(LiveInterpreterPhase.STOPPED, controller.state.value.phase)
    }

    @Test
    fun `empty final removes an unconfirmed hypothesis instead of publishing it`() = runTest {
        val client = FakeClient()
        val controller = LiveInterpreterController(client, FakeMicrophone(), backgroundScope)
        controller.start(selection)
        runCurrent()
        val remote = client.sessions.single()
        remote.transcript("a", null, "unconfirmed", false)
        runCurrent()
        remote.transcript("a", 0, "", true)
        runCurrent()
        assertTrue(controller.state.value.segments.isEmpty())
        controller.stop()
        runCurrent()
    }

    @Test
    fun `flush timeout preserves unfinished original and is not reported as successful stop`() = runTest {
        val client = FakeClient()
        val controller = LiveInterpreterController(client, FakeMicrophone(), backgroundScope)
        controller.start(selection)
        runCurrent()
        val remote = client.sessions.single()
        remote.suppressStopEvent = true
        remote.transcript("a", null, "unfinished original", false)
        runCurrent()
        controller.stop()
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(LiveInterpreterPhase.PAUSED, controller.state.value.phase)
        assertEquals("unfinished original", controller.state.value.segments.single().text)
        assertTrue(controller.state.value.segments.single().interrupted)
        assertTrue(controller.state.value.entries.any { it is LiveTranscriptEntry.Gap })
        assertTrue(remote.closed)
    }

    @Test
    fun `language hint parsing normalizes user input without implementing a language allowlist`() {
        assertEquals(listOf("he", "en", "ru"), parseLiveLanguageHints(" HE, en; ru HE "))
        assertEquals(emptyList(), parseLiveLanguageHints("  "))
        assertEquals(listOf("zz"), parseLiveLanguageHints("zz"))
        assertFailsWith<IllegalArgumentException> { parseLiveLanguageHints("Hebrew") }
    }

    @Test
    fun `native Soniox translation is displayed and suppresses duplicate text translation`() = runTest {
        val client = FakeClient()
        val controller = LiveInterpreterController(
            client, FakeMicrophone(), backgroundScope,
            initialTranslationLanguages = listOf("ru"),
            initialNativeTranslationLanguage = "ru",
        )
        controller.start(selection, "ru")
        runCurrent()
        assertEquals(listOf<String?>("ru"), client.nativeTranslationLanguages)
        val remote = client.sessions.single()
        remote.transcript("one", 0, "שלום.", true)
        remote.nativeTranslation("one", 0, "Привет.", false, "ru", "he", "2")
        remote.nativeTranslation("one", 0, "Здравствуйте.", true, "ru", "he", "2")
        runCurrent()
        val id = "${remote.sessionId}/one"
        assertEquals("Здравствуйте.", controller.state.value.nativeTranslations.getValue(id).text)
        assertTrue(controller.state.value.nativeTranslations.getValue(id).isFinal)
        assertEquals("ru", controller.translations.state.value.nativeLanguage)
        assertTrue(controller.translations.state.value.columns["ru"]?.values.orEmpty().isEmpty())
        controller.stop()
        runCurrent()
        assertEquals(LiveInterpreterPhase.STOPPED, controller.state.value.phase)
    }

    @Test
    fun `translation failures and language changes do not interrupt microphone capture`() = runTest {
        val native = FakeClient()
        var failRussian = true
        val requests = mutableListOf<com.gromozeka.remote.protocol.TranslateLiveTextRequest>()
        val client = object : LiveInterpreterClient by native {
            override suspend fun translate(request: com.gromozeka.remote.protocol.TranslateLiveTextRequest): com.gromozeka.remote.protocol.LiveTextTranslatedResponse {
                requests += request
                check(request.targetLanguage != "ru" || !failRussian) { "Synthetic translation failure" }
                return com.gromozeka.remote.protocol.LiveTextTranslatedResponse(request.revision, request.targetLanguage,
                    request.blocks.map { com.gromozeka.remote.protocol.TranslatedLiveBlock(it.id, "${request.targetLanguage}:${it.text}") })
            }
        }
        val microphone = FakeMicrophone()
        val controller = LiveInterpreterController(client, microphone, backgroundScope, initialTranslationLanguages = listOf("ru"))
        controller.start(AiRuntimeSelection(AiModelConfiguration.Id("asr-test")))
        runCurrent()
        native.sessions.single().transcript("one", 0, "Original stays unchanged.", true)
        runCurrent()
        assertEquals(LiveInterpreterPhase.LISTENING, controller.state.value.phase)
        assertTrue(controller.translations.state.value.columns.getValue("ru").error != null)
        controller.translations.setLanguages(listOf("ru", "he"))
        runCurrent()
        assertTrue(requests.any { it.targetLanguage == "he" && it.blocks.single().text == "Original stays unchanged." })
        failRussian = false
        controller.translations.retry("ru")
        runCurrent()
        assertEquals("Original stays unchanged.", controller.state.value.segments.single().text)
        assertEquals(LiveInterpreterPhase.LISTENING, controller.state.value.phase)
        assertEquals(1, native.starts)
        assertTrue(!microphone.captures.single().cancelled)
        controller.stop()
        runCurrent()
        assertEquals(LiveInterpreterPhase.STOPPED, controller.state.value.phase)
        assertTrue(controller.translations.state.value.captureFinished)
    }

    @Test
    fun `manual commit leaves microphone session and prior originals running`() = runTest {
        val client = FakeClient()
        val microphone = FakeMicrophone()
        val controller = LiveInterpreterController(client, microphone, backgroundScope)
        controller.setManualTurnControl(true)
        controller.start(selection)
        runCurrent()
        assertEquals(listOf(true), client.manualModes)
        val remote = client.sessions.single()
        remote.transcript("first", 0, "Already confirmed.", true)
        runCurrent()
        controller.commit()
        runCurrent()
        assertEquals(1, remote.commits)
        assertFalse(controller.state.value.commitPending)
        assertEquals(LiveInterpreterPhase.LISTENING, controller.state.value.phase)
        assertEquals("Already confirmed.", controller.state.value.segments.single().text)
        assertFalse(remote.closed)
        assertFalse(microphone.captures.single().stopped)
        assertFalse(microphone.captures.single().cancelled)
        assertFailsWith<IllegalStateException> { controller.setManualTurnControl(false) }
        controller.stop()
        runCurrent()
        assertEquals(LiveInterpreterPhase.STOPPED, controller.state.value.phase)
    }

    @Test
    fun `unacknowledged manual commit times out visibly and is not replayed after reconnect`() = runTest {
        val client = FakeClient()
        val controller = LiveInterpreterController(client, FakeMicrophone(), backgroundScope, listOf(100))
        controller.setManualTurnControl(true)
        controller.start(selection)
        runCurrent()
        val old = client.sessions.single()
        old.transcript("first", 0, "Retained original.", true)
        runCurrent()
        old.suppressCommitStatus = true
        controller.commit()
        runCurrent()
        assertTrue(controller.state.value.commitPending)
        controller.commit()
        runCurrent()
        assertEquals(1, old.commits)
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(LiveInterpreterPhase.RECONNECTING, controller.state.value.phase)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, client.starts)
        assertEquals(0, client.sessions.last().commits)
        assertFalse(controller.state.value.commitPending)
        assertEquals("Retained original.", controller.state.value.segments.single().text)
        assertTrue(controller.state.value.entries.any { it is LiveTranscriptEntry.Gap })
        controller.stop()
        runCurrent()
    }

    @Test
    fun `automatic mode and inactive capture do not send manual commits`() = runTest {
        val client = FakeClient()
        val controller = LiveInterpreterController(client, FakeMicrophone(), backgroundScope)
        controller.commit()
        controller.start(selection)
        runCurrent()
        controller.commit()
        runCurrent()
        assertEquals(listOf(false), client.manualModes)
        assertEquals(0, client.sessions.single().commits)
        assertFalse(controller.state.value.commitPending)
        controller.stop()
        runCurrent()
    }

    @Test
    fun `stop cancels a pending commit wait without replaying it in a new session`() = runTest {
        val client = FakeClient()
        val controller = LiveInterpreterController(client, FakeMicrophone(), backgroundScope)
        controller.setManualTurnControl(true)
        controller.start(selection)
        runCurrent()
        client.sessions.single().suppressCommitStatus = true
        controller.commit()
        runCurrent()
        controller.stop()
        runCurrent()
        assertEquals(LiveInterpreterPhase.STOPPED, controller.state.value.phase)
        assertFalse(controller.state.value.commitPending)
        controller.start(selection)
        runCurrent()
        assertEquals(0, client.sessions.last().commits)
        controller.commit()
        runCurrent()
        assertEquals(1, client.sessions.last().commits)
        controller.stop()
        runCurrent()
    }

    @Test
    fun `late commit acknowledgement cannot replace the stopping status`() = runTest {
        val client = FakeClient()
        val controller = LiveInterpreterController(client, FakeMicrophone(), backgroundScope)
        controller.setManualTurnControl(true)
        controller.start(selection)
        runCurrent()
        val remote = client.sessions.single()
        remote.suppressCommitStatus = true
        remote.suppressStopEvent = true
        controller.commit()
        runCurrent()
        controller.stop()
        runCurrent()
        assertEquals(LiveInterpreterPhase.STOPPING, controller.state.value.phase)
        val flushing = controller.state.value.status
        remote.output.send(LiveInterpreterStatusEvent(remote.sessionId, "interpreter.commitSent"))
        runCurrent()
        assertEquals(flushing, controller.state.value.status)
        remote.output.send(LiveInterpreterStoppedEvent(remote.sessionId))
        remote.output.close()
        runCurrent()
        assertEquals(LiveInterpreterPhase.STOPPED, controller.state.value.phase)
    }

    @Test
    fun `synchronously completed Stop cannot be overwritten with stopping state`() = runTest {
        val client = FakeClient()
        val immediateScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val controller = LiveInterpreterController(client, FakeMicrophone(), immediateScope)
        controller.setManualTurnControl(true)
        controller.start(selection)
        assertEquals(LiveInterpreterPhase.LISTENING, controller.state.value.phase)
        controller.stop()
        assertTrue(client.sessions.single().closed)
        assertEquals(LiveInterpreterPhase.STOPPED, controller.state.value.phase)
    }

    private val LiveInterpreterState.segments: List<LiveTranscriptEntry.Segment>
        get() = entries.filterIsInstance<LiveTranscriptEntry.Segment>()

    private class FakeClient : LiveInterpreterClient {
        override suspend fun translate(request: com.gromozeka.remote.protocol.TranslateLiveTextRequest): com.gromozeka.remote.protocol.LiveTextTranslatedResponse =
            error("Unexpected translation in an ASR-only test")
        var starts = 0
        var startFailure: Exception? = null
        val sessions = mutableListOf<FakeRemote>()
        val languages = mutableListOf<List<String>>()
        val manualModes = mutableListOf<Boolean>()
        val nativeTranslationLanguages = mutableListOf<String?>()
        override suspend fun start(languages: List<String>, selection: AiRuntimeSelection, manualTurnControl: Boolean): LiveInterpreterRemoteSession =
            start(languages, selection, manualTurnControl, null)

        override suspend fun start(
            languages: List<String>,
            selection: AiRuntimeSelection,
            manualTurnControl: Boolean,
            nativeTranslationLanguage: String?,
        ): LiveInterpreterRemoteSession {
            starts += 1
            this.languages += languages.toList()
            manualModes += manualTurnControl
            nativeTranslationLanguages += nativeTranslationLanguage
            startFailure?.let { throw it }
            return FakeRemote("session-${sessions.size}").also { sessions += it }
        }
    }

    private class FakeRemote(override val sessionId: String) : LiveInterpreterRemoteSession {
        val output = Channel<ServerPayload>(Channel.UNLIMITED)
        override val events = output.receiveAsFlow()
        var closed = false
        var suppressStopEvent = false
        var suppressCommitStatus = false
        var commits = 0
        override suspend fun commit() {
            commits += 1
            if (!suppressCommitStatus) output.send(LiveInterpreterStatusEvent(sessionId, "interpreter.commitSent"))
        }
        private var stopped = false
        init { output.trySend(LiveInterpreterStatusEvent(sessionId, "interpreter.status.listening")) }
        suspend fun transcript(id: String, position: Int?, text: String, final: Boolean) {
            output.send(LiveInterpreterTranscriptEvent(sessionId, id, position, text, final))
        }
        suspend fun nativeTranslation(
            id: String,
            position: Int,
            text: String,
            final: Boolean,
            targetLanguage: String,
            sourceLanguage: String?,
            speakerId: String?,
        ) {
            output.send(LiveInterpreterNativeTranslationEvent(
                sessionId, id, position, text, final, targetLanguage, sourceLanguage, speakerId,
            ))
        }
        override suspend fun sendAudioChunk(chunk: RemotePcmAudioChunk) = Unit
        override suspend fun stop() {
            if (stopped) return
            stopped = true
            if (suppressStopEvent) return
            output.send(LiveInterpreterStoppedEvent(sessionId))
            output.close()
        }
        override suspend fun close() { closed = true; output.close() }
    }

    private class FakeMicrophone : ClientLiveAudioStreamer {
        override val unavailableReason: LocalizedText? = null
        val captures = mutableListOf<FakeCapture>()
        override suspend fun start(scope: CoroutineScope, onChunk: suspend (RemotePcmAudioChunk) -> Unit): ClientLiveAudioStreamingSession =
            FakeCapture(scope).also { captures += it }
    }

    private class FakeCapture(scope: CoroutineScope) : ClientLiveAudioStreamingSession {
        val failure = CompletableDeferred<Unit>()
        private val job: Job = scope.launch { failure.await() }
        var cancelled = false
        var stopped = false
        override suspend fun stop() { stopped = true; job.cancelAndJoin() }
        override fun cancel() { cancelled = true; job.cancel() }
    }
}
