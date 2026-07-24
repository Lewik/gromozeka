package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.remote.protocol.ClientActivityKind
import com.gromozeka.remote.protocol.ClientInstanceId
import com.gromozeka.remote.protocol.ClientSessionId
import com.gromozeka.remote.protocol.PlayMessageTtsDirective
import com.gromozeka.remote.protocol.RegisterClientSessionCommand
import com.gromozeka.remote.protocol.RemoteClientPlatform
import com.gromozeka.remote.protocol.RemoteProtocolEncoding
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.StopTtsDirective
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClientPresentationRegistryTest {
    @Test
    fun routesSpeechOnlyToMostRecentlyActiveClient() = runBlocking {
        val registry = ClientPresentationRegistry()
        val firstClientEvents = mutableListOf<ServerPayload>()
        val secondClientEvents = mutableListOf<ServerPayload>()
        registry.registerClient("connection-1", "client-1", "session-1", firstClientEvents)
        registry.registerClient("connection-2", "client-2", "session-2", secondClientEvents)

        registry.activate("connection-1", ClientActivityKind.USER_INTERACTION)
        assertTrue(registry.present(assistantMessage("message-1", "First response")))
        assertEquals("First response", assertIs<PlayMessageTtsDirective>(firstClientEvents.single()).text)
        assertTrue(secondClientEvents.isEmpty())

        registry.activate("connection-2", ClientActivityKind.WINDOW_FOCUSED)
        assertSame(StopTtsDirective, firstClientEvents.last())
        assertTrue(registry.present(assistantMessage("message-2", "Second response")))
        assertEquals("Second response", assertIs<PlayMessageTtsDirective>(secondClientEvents.single()).text)
    }

    @Test
    fun presentsEachMessageAtMostOnceAcrossSubscriptions() = runBlocking {
        val registry = ClientPresentationRegistry()
        val events = mutableListOf<ServerPayload>()
        registry.registerClient("connection-1", "client-1", "session-1", events)
        registry.activate("connection-1", ClientActivityKind.USER_INTERACTION)
        val message = assistantMessage("message-1", "Only once")

        assertTrue(registry.present(message))
        assertFalse(registry.present(message))

        assertEquals(1, events.filterIsInstance<PlayMessageTtsDirective>().size)
    }

    @Test
    fun activeSessionCanReconnectWithoutStealingActivityFromAnotherSession() = runBlocking {
        val registry = ClientPresentationRegistry()
        val firstConnectionEvents = mutableListOf<ServerPayload>()
        val reconnectedEvents = mutableListOf<ServerPayload>()
        registry.registerClient("connection-1", "client-1", "session-1", firstConnectionEvents)
        registry.activate("connection-1", ClientActivityKind.USER_INTERACTION)
        registry.disconnect("connection-1")

        assertFalse(registry.present(assistantMessage("message-1", "While disconnected")))

        registry.registerClient("connection-2", "client-1", "session-1", reconnectedEvents)
        assertTrue(registry.present(assistantMessage("message-2", "After reconnect")))

        assertEquals("After reconnect", assertIs<PlayMessageTtsDirective>(reconnectedEvents.single()).text)
    }

    @Test
    fun switchingClientsCannotSendStopBeforeAnInFlightPlay() = runBlocking {
        val registry = ClientPresentationRegistry()
        val playStarted = CompletableDeferred<Unit>()
        val releasePlay = CompletableDeferred<Unit>()
        val firstClientEvents = mutableListOf<ServerPayload>()
        val secondClientEvents = mutableListOf<ServerPayload>()
        registry.register(
            connectionId = "connection-1",
            command = registration("client-1", "session-1"),
            encoding = RemoteProtocolEncoding.CBOR,
            send = { payload, _ ->
                if (payload is PlayMessageTtsDirective) {
                    playStarted.complete(Unit)
                    releasePlay.await()
                }
                firstClientEvents += payload
            },
        )
        registry.registerClient("connection-2", "client-2", "session-2", secondClientEvents)
        registry.activate("connection-1", ClientActivityKind.USER_INTERACTION)

        val presentation = async {
            registry.present(assistantMessage("message-1", "First response"))
        }
        playStarted.await()
        val activationStarted = CompletableDeferred<Unit>()
        val activation = async {
            activationStarted.complete(Unit)
            registry.activate("connection-2", ClientActivityKind.USER_INTERACTION)
        }

        activationStarted.await()
        yield()
        assertFalse(activation.isCompleted)
        releasePlay.complete(Unit)
        assertTrue(presentation.await())
        activation.await()

        assertIs<PlayMessageTtsDirective>(firstClientEvents[0])
        assertSame(StopTtsDirective, firstClientEvents[1])
    }

    private suspend fun ClientPresentationRegistry.registerClient(
        connectionId: String,
        clientInstanceId: String,
        clientSessionId: String,
        events: MutableList<ServerPayload>,
    ) {
        register(
            connectionId = connectionId,
            command = registration(clientInstanceId, clientSessionId),
            encoding = RemoteProtocolEncoding.CBOR,
            send = { payload, _ -> events += payload },
        )
    }

    private fun registration(
        clientInstanceId: String,
        clientSessionId: String,
    ) = RegisterClientSessionCommand(
        clientInstanceId = ClientInstanceId(clientInstanceId),
        clientSessionId = ClientSessionId(clientSessionId),
        platform = RemoteClientPlatform.DESKTOP,
    )

    private fun assistantMessage(
        id: String,
        ttsText: String,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                Conversation.Message.ContentItem.AssistantMessage(
                    structured = Conversation.Message.StructuredText(
                        fullText = ttsText,
                        ttsText = ttsText,
                        voiceTone = "warm",
                    ),
                )
            ),
            createdAt = Clock.System.now(),
        )
}
