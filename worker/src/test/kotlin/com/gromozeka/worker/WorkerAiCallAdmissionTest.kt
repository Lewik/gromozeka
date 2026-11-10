package com.gromozeka.worker

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.*
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.mockito.Mockito
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Clock

class WorkerAiCallAdmissionTest {
    @Test
    fun `lost completion acknowledgement is replayed from the journal without repeating inference`() = runBlocking {
        withTimeout(15_000) {
            fixture { settings, identity, scope ->
                val journal = JvmWorkerRequestJournal(settings, identity)
                val outbound = WorkerGatewayOutbound(ConversationRuntimeWorkerProperties(identity.workerId.value))
                val state = PeerState(identity.workerId.value)
                var peer = connect(scope, outbound, state)
                val admission = WorkerAiCallAdmission(outbound, identity, journal, scope)
                admission.initialize()
                val executions = AtomicInteger()
                try {
                    state.dropFinishAck.set(true)
                    assertEquals(42, withContext(AiAdmissionWorkerContext("root")) {
                        admission.execute(connection) { executions.incrementAndGet(); 42 }
                    })
                    assertEquals(1, journal.loadAiAdmissions().size)
                    peer.close(outbound)
                    peer = connect(scope, outbound, state)
                    waitUntil { journal.loadAiAdmissions().isEmpty() }
                    assertEquals(1, executions.get())
                    assertEquals(1, state.acquisitions.get())
                    assertEquals(1, state.finishEffects.get())
                    assertTrue(state.finishAttempts.get() >= 2)
                } finally {
                    admission.stop()
                    peer.close(outbound)
                    journal.close()
                }
            }
        }
    }

    @Test
    fun `background ownership is journaled and accepted before use and recovered after process restart`() = runBlocking {
        withTimeout(15_000) {
            fixture { settings, identity, scope ->
                val outbound = WorkerGatewayOutbound(ConversationRuntimeWorkerProperties(identity.workerId.value))
                val state = PeerState(identity.workerId.value)
                val peer = connect(scope, outbound, state)
                var journal = JvmWorkerRequestJournal(settings, identity)
                var admission = WorkerAiCallAdmission(outbound, identity, journal, scope)
                admission.initialize()
                try {
                    withContext(AiAdmissionWorkerContext("capture-start")) {
                        admission.openSession(connection, "SPEECH_TO_TEXT", "capture")
                    }
                    val stored = journal.loadAiAdmissions().single()
                    assertEquals("capture", stored.operation.backgroundSessionId)
                    assertTrue(stored.operation.id in state.accepted)
                    assertEquals(1, state.active.size)
                    admission.stop()
                    journal.close()
                    journal = JvmWorkerRequestJournal(settings, identity.copy(sessionId = ConversationRuntimeWorkerSessionId("second")))
                    admission = WorkerAiCallAdmission(outbound, identity, journal, scope)
                    admission.initialize()
                    waitUntil { journal.loadAiAdmissions().isEmpty() }
                    assertTrue(state.active.isEmpty())
                    assertEquals(1, state.acquisitions.get())
                    assertEquals(listOf(AiAdmissionOutcome.OWNER_STOPPED), state.outcomes.toList())
                } finally {
                    admission.stop()
                    peer.close(outbound)
                    journal.close()
                }
            }
        }
    }

    @Test
    fun `a Worker operation without a Server owner fails before requesting a grant`() = runBlocking {
        fixture { settings, identity, scope ->
            val journal = JvmWorkerRequestJournal(settings, identity)
            val outbound = WorkerGatewayOutbound(ConversationRuntimeWorkerProperties(identity.workerId.value))
            val state = PeerState(identity.workerId.value)
            val peer = connect(scope, outbound, state)
            val admission = WorkerAiCallAdmission(outbound, identity, journal, scope)
            admission.initialize()
            try {
                assertFailsWith<AiAdmissionException> { admission.execute(connection) { error("Must not execute") } }
                assertEquals(0, state.acquisitions.get())
            } finally {
                admission.stop()
                peer.close(outbound)
                journal.close()
            }
        }
    }

    private class PeerState(val workerId: String) {
        val active = ConcurrentHashMap<String, AiAdmissionLease>()
        val accepted = ConcurrentHashMap.newKeySet<String>()
        val acquisitions = AtomicInteger()
        val finishEffects = AtomicInteger()
        val finishAttempts = AtomicInteger()
        val dropFinishAck = AtomicBoolean()
        val outcomes = CopyOnWriteArrayList<AiAdmissionOutcome>()
    }

    private class Peer(val outgoing: Channel<WorkerGatewayMessage>, val job: Job) {
        suspend fun close(outbound: WorkerGatewayOutbound) {
            outbound.detach(outgoing)
            outgoing.close()
            job.cancelAndJoin()
        }
    }

    private suspend fun connect(scope: CoroutineScope, outbound: WorkerGatewayOutbound, state: PeerState): Peer {
        val outgoing = Channel<WorkerGatewayMessage>(Channel.UNLIMITED)
        outbound.attach(outgoing)
        val job = scope.launch {
            for (message in outgoing) {
                val request = assertIs<WorkerGatewayMessage.Request>(message)
                assertEquals(WorkerGatewayOperation.AI_ADMISSION, request.operation)
                val payload = when (val command = AiAdmissionGatewayCodec.decode(request.payload)) {
                    is AiAdmissionCommand.Acquire -> {
                        state.acquisitions.incrementAndGet()
                        val lease = AiAdmissionLease(command.operation, AiConcurrencyGroup.DEFAULT_ID, Clock.System.now(), state.workerId, command.workerRequestId)
                        check(state.active.putIfAbsent(command.operation.id, lease) == null)
                        AiAdmissionGatewayCodec.encodeLease(lease)
                    }
                    is AiAdmissionCommand.Accept -> {
                        check(state.active.containsKey(command.operationId))
                        state.accepted += command.operationId
                        byteArrayOf()
                    }
                    is AiAdmissionCommand.Finish -> {
                        state.finishAttempts.incrementAndGet()
                        if (state.active.remove(command.operationId) != null) {
                            state.finishEffects.incrementAndGet()
                            state.outcomes += command.outcome
                        }
                        if (state.dropFinishAck.compareAndSet(true, false)) {
                            outbound.detach(outgoing)
                            continue
                        }
                        byteArrayOf()
                    }
                }
                assertTrue(outbound.accept(WorkerGatewayMessage.Response(request.id, WorkerGatewayMessage.Response.Status.SUCCEEDED, payload)))
            }
        }
        return Peer(outgoing, job)
    }

    private suspend fun waitUntil(condition: suspend () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    private suspend fun fixture(block: suspend (SettingsProvider, ConversationRuntimeWorkerIdentity, CoroutineScope) -> Unit) {
        val directory = Files.createTempDirectory("worker-ai-admission-")
        val settings = Mockito.mock(SettingsProvider::class.java)
        Mockito.`when`(settings.homeDirectory).thenReturn(directory.toString())
        val identity = ConversationRuntimeWorkerIdentity(ConversationRuntimeWorkerId("test-worker"), ConversationRuntimeWorkerSessionId("first"))
        val owner = SupervisorJob(currentCoroutineContext()[Job])
        val scope = CoroutineScope(currentCoroutineContext() + owner)
        try { block(settings, identity, scope) }
        finally {
            owner.cancelAndJoin()
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private companion object {
        val connection = AiConnection.OpenAiApi(AiConnection.Id("test-connection"), "Test")
    }
}
