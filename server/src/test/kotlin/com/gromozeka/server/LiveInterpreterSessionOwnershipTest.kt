package com.gromozeka.server

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.infrastructure.ai.openai.SttService
import com.gromozeka.remote.protocol.LiveInterpreterTranscriptChunkCommand
import com.gromozeka.remote.protocol.RemoteLiveTranscriptChunk
import com.gromozeka.remote.protocol.StartLiveInterpreterRequest
import com.gromozeka.remote.protocol.StopLiveInterpreterCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveInterpreterSessionOwnershipTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("test-model"))
    private val configurationProvider = object : AiConfigurationProvider {
        override val snapshotFlow: StateFlow<AiCatalogSnapshot?>
            get() = error("Not used by this test")
        override val snapshot: AiCatalogSnapshot
            get() = error("Not used by this test")

        override fun runtimeSelectionFor(purpose: AiRuntimeAssignment.Purpose): AiRuntimeSelection =
            runtimeSelection

        override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime =
            error("Not used by this test")
    }
    private val service = LiveInterpreterApplicationService(
        sttService = Mockito.mock(SttService::class.java),
        aiRuntimeProvider = Mockito.mock(AiRuntimeProvider::class.java),
        aiConfigurationProvider = configurationProvider,
        scope = scope,
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `only originating connection can append or stop a session`() = runBlocking {
        val owner = owner("user-1", "connection-1")
        val sessionId = service.start(owner, StartLiveInterpreterRequest()) {}.sessionId
        val foreignConnections = listOf(
            owner("user-1", "connection-2"),
            owner("user-2", "connection-1"),
        )

        foreignConnections.forEach { foreignOwner ->
            assertFalse(
                service.append(
                    foreignOwner,
                    LiveInterpreterTranscriptChunkCommand(
                        sessionId = sessionId,
                        chunk = RemoteLiveTranscriptChunk(1, "private transcript"),
                    ),
                )
            )
            assertFalse(service.stop(foreignOwner, StopLiveInterpreterCommand(sessionId)))
        }

        assertTrue(service.stop(owner, StopLiveInterpreterCommand(sessionId)))
    }

    @Test
    fun `disconnect cleanup cancels only sessions owned by that connection`() = runBlocking {
        val disconnectedOwner = owner("user-1", "connection-1")
        val remainingOwner = owner("user-1", "connection-2")
        val disconnectedSessionId =
            service.start(disconnectedOwner, StartLiveInterpreterRequest()) {}.sessionId
        val remainingSessionId =
            service.start(remainingOwner, StartLiveInterpreterRequest()) {}.sessionId

        assertEquals(1, service.stopOwnedBy(disconnectedOwner))
        assertFalse(service.stop(disconnectedOwner, StopLiveInterpreterCommand(disconnectedSessionId)))
        assertTrue(service.stop(remainingOwner, StopLiveInterpreterCommand(remainingSessionId)))
    }

    @Test
    fun `disconnect can cancel a session that is already stopping gracefully`() = runBlocking {
        val owner = owner("user-1", "connection-1")
        val eventSinkEntered = CompletableDeferred<Unit>()
        val keepEventSinkOpen = CompletableDeferred<Unit>()
        val sessionId = service.start(owner, StartLiveInterpreterRequest()) {
            eventSinkEntered.complete(Unit)
            keepEventSinkOpen.await()
        }.sessionId
        eventSinkEntered.await()

        assertTrue(service.stop(owner, StopLiveInterpreterCommand(sessionId)))
        assertEquals(1, service.stopOwnedBy(owner))
        assertEquals(0, service.stopOwnedBy(owner))
    }

    private fun owner(
        userId: String,
        connectionId: String,
    ): LiveInterpreterSessionOwner =
        LiveInterpreterSessionOwner(
            userId = User.Id(userId),
            connectionId = connectionId,
        )
}
