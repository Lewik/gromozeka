package com.gromozeka.server

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.LiveTranscriptionEvent
import com.gromozeka.domain.service.LiveTranscriptionException
import com.gromozeka.domain.service.LiveTranscriptionProvider
import com.gromozeka.domain.service.LiveTranscriptionSession
import com.gromozeka.domain.service.LiveTranscriptionTurnDetection
import com.gromozeka.remote.protocol.LiveInterpreterAudioChunkCommand
import com.gromozeka.remote.protocol.LiveInterpreterFailedEvent
import com.gromozeka.remote.protocol.LiveInterpreterStatusEvent
import com.gromozeka.remote.protocol.LiveInterpreterStoppedEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranscriptEvent
import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.StartLiveInterpreterRequest
import com.gromozeka.remote.protocol.StopLiveInterpreterCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mockito.Mockito
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LiveInterpreterSessionOwnershipTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val provider = FakeProvider()
    private val selection = AiRuntimeSelection(AiModelConfiguration.Id("live-test-model"))
    private val service = LiveInterpreterApplicationService(provider, Mockito.mock(AiConfigurationProvider::class.java), scope)

    @AfterTest
    fun tearDown() {
        runBlocking {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun `only originating connection can append or stop a session`() = runBlocking {
        val owner = owner("user-1", "connection-1")
        service.start(owner, request("live-1")) {}
        listOf(owner("user-1", "connection-2"), owner("user-2", "connection-1")).forEach { foreign ->
            assertFalse(service.append(foreign, audio("live-1")))
            assertFalse(service.stop(foreign, StopLiveInterpreterCommand("live-1")))
        }
        assertTrue(service.stop(owner, StopLiveInterpreterCommand("live-1")))
    }

    @Test
    fun `disconnect cleanup cancels only sessions owned by that connection`() = runBlocking {
        val disconnected = owner("user-1", "connection-1")
        val remaining = owner("user-1", "connection-2")
        service.start(disconnected, request("live-1")) {}
        service.start(remaining, request("live-2")) {}
        assertEquals(1, service.stopOwnedBy(disconnected))
        assertFalse(service.stop(disconnected, StopLiveInterpreterCommand("live-1")))
        assertTrue(service.stop(remaining, StopLiveInterpreterCommand("live-2")))
    }

    @Test
    fun `disconnect can cancel a session that is already stopping gracefully`() = runBlocking {
        val owner = owner("user-1", "connection-1")
        val entered = CompletableDeferred<Unit>()
        val block = CompletableDeferred<Unit>()
        service.start(owner, request("live-1")) { event ->
            if (event is LiveInterpreterStatusEvent) { entered.complete(Unit); block.await() }
        }
        withTimeout(3_000) { entered.await() }
        assertTrue(service.stop(owner, StopLiveInterpreterCommand("live-1")))
        assertEquals(1, service.stopOwnedBy(owner))
        assertEquals(0, service.stopOwnedBy(owner))
    }

    @Test
    fun `PCM is forwarded once and provider text remains original`() = runBlocking {
        val owner = owner("user-1", "connection-1")
        val events = Channel<ServerPayload>(Channel.UNLIMITED)
        service.start(owner, request("live-1").copy(languages = emptyList())) { events.send(it) }
        val upstream = withTimeout(3_000) { provider.opened.receive() }
        events.next<LiveInterpreterStatusEvent>()
        assertEquals(emptyList(), provider.lastLanguages)
        assertEquals(selection, provider.lastSelection)
        val bytes = ByteArray(3_200) { (it % 127).toByte() }
        assertTrue(service.append(owner, audio("live-1", bytes)))
        assertContentEquals(bytes, withTimeout(3_000) { upstream.audio.receive() })
        assertTrue(upstream.audio.tryReceive().isFailure)
        upstream.output.send(LiveTranscriptionEvent.Transcript("a", "שלום", false))
        val partial = events.next<LiveInterpreterTranscriptEvent>()
        assertEquals("שלום", partial.text)
        assertEquals(null, partial.sequenceNumber)
        assertFalse(partial.isFinal)
        val silence = ByteArray(25_600)
        assertTrue(service.append(owner, LiveInterpreterAudioChunkCommand("live-1", RemotePcmAudioChunk(1, silence))))
        assertContentEquals(silence, withTimeout(3_000) { upstream.audio.receive() })
        assertTrue(upstream.commits.tryReceive().isFailure)
        assertTrue(service.append(owner, LiveInterpreterAudioChunkCommand("live-1", RemotePcmAudioChunk(2, silence))))
        assertContentEquals(silence, withTimeout(3_000) { upstream.audio.receive() })
        withTimeout(3_000) { upstream.commits.receive() }
        upstream.output.send(LiveTranscriptionEvent.Committed("a", null))
        upstream.output.send(LiveTranscriptionEvent.Transcript("a", "שלום world", true))
        events.next<LiveInterpreterTranscriptEvent>()
        val final = events.next<LiveInterpreterTranscriptEvent>()
        assertEquals("שלום world", final.text)
        assertEquals(0, final.sequenceNumber)
        assertTrue(final.isFinal)
        assertTrue(service.stop(owner, StopLiveInterpreterCommand("live-1")))
        events.next<LiveInterpreterStoppedEvent>()
        assertEquals(AiAdmissionOutcome.SUCCEEDED, upstream.closed.await())
    }

    @Test
    fun `provider owns turn boundaries without competing silence or long turn commits`() = runBlocking {
        provider.turnDetection = LiveTranscriptionTurnDetection.PROVIDER
        val owner = owner("user-1", "connection-1")
        val events = Channel<ServerPayload>(Channel.UNLIMITED)
        service.start(owner, request("provider-vad")) { events.send(it) }
        val upstream = withTimeout(3_000) { provider.opened.receive() }
        events.next<LiveInterpreterStatusEvent>()
        for (sequence in 0 until 22) {
            val pcm = ByteArray(32_000) { if (sequence < 20) (it % 127).toByte() else 0 }
            assertTrue(service.append(owner, LiveInterpreterAudioChunkCommand("provider-vad", RemotePcmAudioChunk(sequence, pcm))))
            assertContentEquals(pcm, withTimeout(3_000) { upstream.audio.receive() })
        }
        assertTrue(upstream.commits.tryReceive().isFailure)
        upstream.output.send(LiveTranscriptionEvent.Committed("native-a", null))
        upstream.output.send(LiveTranscriptionEvent.Transcript("native-a", "One complete thought", true))
        assertEquals("One complete thought", events.next<LiveInterpreterTranscriptEvent>().text)
        assertTrue(service.stop(owner, StopLiveInterpreterCommand("provider-vad")))
        events.next<LiveInterpreterStoppedEvent>()
        assertEquals(AiAdmissionOutcome.SUCCEEDED, upstream.closed.await())
        assertTrue(upstream.commits.tryReceive().isFailure)
    }

    @Test
    fun `provider failure is explicit and does not stop another session`() = runBlocking {
        val owner = owner("user-1", "connection-1")
        val events = Channel<ServerPayload>(Channel.UNLIMITED)
        service.start(owner, request("live-1")) { events.send(it) }
        val failed = withTimeout(3_000) { provider.opened.receive() }
        events.next<LiveInterpreterStatusEvent>()
        val remainingEvents = Channel<ServerPayload>(Channel.UNLIMITED)
        service.start(owner, request("live-2")) { remainingEvents.send(it) }
        val remaining = withTimeout(3_000) { provider.opened.receive() }
        remainingEvents.next<LiveInterpreterStatusEvent>()
        failed.output.close(LiveTranscriptionException("synthetic network failure", retryable = true))
        assertTrue(events.next<LiveInterpreterFailedEvent>().retryable)
        events.next<LiveInterpreterStoppedEvent>()
        assertEquals(AiAdmissionOutcome.FAILED, failed.closed.await())
        assertTrue(scope.isActive)
        assertTrue(service.append(owner, audio("live-2")))
        withTimeout(3_000) { remaining.audio.receive() }
        assertTrue(service.stop(owner, StopLiveInterpreterCommand("live-2")))
    }

    @Test
    fun `invalid audio order fails that stage without forwarding corrupt input`() = runBlocking {
        val owner = owner("user-1", "connection-1")
        val events = Channel<ServerPayload>(Channel.UNLIMITED)
        service.start(owner, request("live-1")) { events.send(it) }
        val upstream = withTimeout(3_000) { provider.opened.receive() }
        events.next<LiveInterpreterStatusEvent>()
        assertTrue(service.append(owner, audio("live-1").copy(chunk = RemotePcmAudioChunk(2, ByteArray(3_200)))))
        assertFalse(events.next<LiveInterpreterFailedEvent>().retryable)
        events.next<LiveInterpreterStoppedEvent>()
        assertTrue(upstream.audio.tryReceive().isFailure)
        assertTrue(scope.isActive)
    }

    @Test
    fun `flush timeout is not mistaken for user cancellation`() = runBlocking {
        val owner = owner("user-1", "connection-1")
        val events = Channel<ServerPayload>(Channel.UNLIMITED)
        service.start(owner, request("live-1")) { events.send(it) }
        val upstream = withTimeout(3_000) { provider.opened.receive() }
        events.next<LiveInterpreterStatusEvent>()
        upstream.finishAction = { withTimeout(1) { awaitCancellation() } }
        service.stop(owner, StopLiveInterpreterCommand("live-1"))
        assertTrue(events.next<LiveInterpreterFailedEvent>().retryable)
        events.next<LiveInterpreterStoppedEvent>()
        assertEquals(AiAdmissionOutcome.FAILED, upstream.closed.await())
    }

    @Test
    fun `stop received before delayed start prevents opening a hidden provider session`() {
        val owner = owner("user-1", "connection-1")
        assertFalse(service.stop(owner, StopLiveInterpreterCommand("late-start")))
        assertFailsWith<IllegalStateException> { service.start(owner, request("late-start")) {} }
        assertTrue(provider.opened.tryReceive().isFailure)
    }

    @Test
    fun `disconnected connection owner cannot start again through a delayed RPC`() = runBlocking {
        val owner = owner("user-1", "connection-1")
        assertEquals(0, service.stopOwnedBy(owner))
        assertFailsWith<IllegalStateException> { service.start(owner, request("late-start")) {} }
        assertTrue(provider.opened.tryReceive().isFailure)
    }

    @Test
    fun `immediate stop cannot observe an uninitialized session job`() {
        val owner = owner("user-1", "connection-1")
        repeat(100) { index ->
            val id = "immediate-$index"
            service.start(owner, request(id)) {}
            assertTrue(service.stop(owner, StopLiveInterpreterCommand(id)))
            assertFailsWith<IllegalStateException> { service.start(owner, request(id)) {} }
        }
    }

    private fun request(id: String) = StartLiveInterpreterRequest(id, transcriptionRuntimeSelection = selection)
    private fun owner(user: String, connection: String) = LiveInterpreterSessionOwner(User.Id(user), connection)
    private fun audio(id: String, bytes: ByteArray = ByteArray(3_200)) =
        LiveInterpreterAudioChunkCommand(id, RemotePcmAudioChunk(0, bytes))

    private suspend inline fun <reified T : ServerPayload> Channel<ServerPayload>.next(): T = withTimeout(3_000) {
        var event = receive()
        while (event !is T) event = receive()
        event
    }

    private class FakeProvider : LiveTranscriptionProvider {
        val opened = Channel<FakeSession>(Channel.UNLIMITED)
        var lastLanguages: List<String>? = null
        var lastSelection: AiRuntimeSelection? = null
        var turnDetection = LiveTranscriptionTurnDetection.CLIENT
        override suspend fun open(
            userStableId: String,
            selection: AiRuntimeSelection,
            languages: List<String>,
            nativeTranslationLanguage: String?,
        ): LiveTranscriptionSession {
            lastLanguages = languages
            lastSelection = selection
            return FakeSession(turnDetection).also { opened.send(it) }
        }
    }

    private class FakeSession(override val turnDetection: LiveTranscriptionTurnDetection) : LiveTranscriptionSession {
        val output = Channel<LiveTranscriptionEvent>(Channel.UNLIMITED)
        override val events = output.receiveAsFlow()
        val audio = Channel<ByteArray>(Channel.UNLIMITED)
        val closed = CompletableDeferred<AiAdmissionOutcome>()
        val commits = Channel<Unit>(Channel.UNLIMITED)
        var finishAction: suspend () -> Unit = { output.close() }
        override suspend fun appendAudio(pcm: ByteArray) { audio.send(pcm.copyOf()) }
        override suspend fun commit() { commits.send(Unit) }
        override suspend fun finishInput() = finishAction()
        override suspend fun close(outcome: AiAdmissionOutcome) { closed.complete(outcome); output.close() }
    }
}
