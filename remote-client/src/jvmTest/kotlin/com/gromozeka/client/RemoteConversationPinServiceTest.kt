package com.gromozeka.client

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.remote.protocol.*
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import kotlin.time.Instant
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** Real client codec/WS callbacks against an in-process test peer, not production Server/SQL e2e. */
class RemoteConversationPinServiceTest {
    private val cid = Conversation.Id("client-pin-conversation")
    private val id = ConversationPin.Id("server-generated-pin")
    private val literal = "  שלום\r\nКириллица 👩🏽‍💻 100%\t "
    private fun pin(text: String = literal, revision: Long = 1) = ConversationPin(
        id, cid, text, 9, revision, Instant.fromEpochMilliseconds(123), Instant.fromEpochMilliseconds(123),
    )

    @Test fun `CRUD forwards literal text creation identity and observed revisions`() {
        val calls = mutableListOf<ClientRequest>()
        var stored: ConversationPin? = null
        withClient({ request ->
            calls += request
            when (request) {
                is CreateConversationPinRequest -> {
                    assertEquals(cid, request.conversationId); assertEquals(literal, request.text)
                    assertEquals("stable-key", request.creationKey)
                    stored = pin(request.text).copy(source = request.source)
                    ConversationPinResponse(stored)
                }
                is ListConversationPinsRequest -> ConversationPinsResponse(listOfNotNull(stored))
                is GetConversationPinRequest -> { assertEquals(id, request.pinId); ConversationPinResponse(stored) }
                is UpdateConversationPinRequest -> {
                    assertEquals(1L, request.expectedRevision); assertEquals(id, request.pinId)
                    stored = requireNotNull(stored).copy(text = request.text, revision = 2)
                    ConversationPinResponse(stored)
                }
                is DeleteConversationPinRequest -> {
                    assertEquals(2L, request.expectedRevision); assertEquals(id, request.pinId)
                    stored = null; OperationResultResponse(true)
                }
                else -> error("Unexpected test request")
            }
        }) { client ->
            val service = RemoteConversationPinService(client)
            val source = ConversationPinSource(cid, Conversation.Message.Id("source"))
            val created = service.create(cid, literal, source, "stable-key")
            assertEquals(source, created.source); assertEquals(literal, service.get(cid, id)?.text)
            assertEquals(listOf(created), service.list(cid))
            val edited = service.update(cid, id, created.revision, " changed\n")
            assertEquals(" changed\n", edited.text); assertEquals(source, edited.source)
            service.delete(cid, id, edited.revision)
            assertNull(service.get(cid, id))
            assertEquals(6, calls.size)
        }
    }

    @Test fun `fixed wire failure types become domain failures without raw server text or automatic retry`() {
        var code: String? = null
        var calls = 0
        withClient({ calls++; ErrorResponse("PRIVATE_PIN_AND_STORAGE_DETAIL", code) }) { client ->
            val service = RemoteConversationPinService(client)
            val codes = listOf("PIN_CONFLICT", "PIN_NOT_FOUND", "PIN_LIMIT_EXCEEDED", "PIN_TARGET_UNAVAILABLE", "UNKNOWN_PRIVATE_TYPE")
            for (value in codes) {
                code = value
                val error = assertFailsWith<Exception> { service.get(cid, id) }
                when (value) {
                    "PIN_CONFLICT" -> assertIs<ConversationPinConflictException>(error)
                    "PIN_NOT_FOUND" -> assertIs<ConversationPinNotFoundException>(error)
                    "PIN_LIMIT_EXCEEDED" -> assertIs<ConversationPinLimitException>(error)
                    "PIN_TARGET_UNAVAILABLE" -> assertIs<ProjectAccessDeniedException>(error)
                    else -> assertIs<ConversationPinOperationFailedException>(error)
                }
                assertFalse(error.toString().contains("PRIVATE_")); assertNull(error.cause); assertTrue(error.suppressed.isEmpty())
            }
            assertEquals(codes.size, calls)
        }
    }

    @Test fun `failed delete and empty create response cannot be reported as success`() {
        withClient({ request ->
            if (request is DeleteConversationPinRequest) OperationResultResponse(false, error = "PRIVATE_DETAIL")
            else ConversationPinResponse(null)
        }) { client ->
            val service = RemoteConversationPinService(client)
            assertFailsWith<ConversationPinOperationFailedException> { service.delete(cid, id, 1) }
            assertFailsWith<ConversationPinOperationFailedException> { service.create(cid, literal, null, "key") }
        }
    }

    @Test fun `pin preserving restart sanitizes incompatible replies without fallback or retry`() {
        val calls = mutableListOf<ClientRequest>()
        var response: ServerResponse = ErrorResponse("PRIVATE_PIN_AND_STORAGE_DETAIL", "UNKNOWN")
        withClient({ request -> calls += request; response }) { client ->
            val cases = listOf<ServerResponse>(
                ErrorResponse("PRIVATE_PIN_AND_STORAGE_DETAIL", "UNKNOWN"),
                ConversationPinResponse(pin("PRIVATE_UNEXPECTED_PIN_BODY")),
                ConversationResponse(null),
                ErrorResponse("PRIVATE_DENIAL_DETAIL", "PIN_TARGET_UNAVAILABLE"),
            )
            val service = RemoteConversationService(client)
            for (reply in cases) {
                response = reply
                val error = assertFailsWith<Exception> {
                    service.createWithCopiedPins(Project.Id("project"), emptySet(), cid, "")
                }
                if (reply is ErrorResponse && reply.type == "PIN_TARGET_UNAVAILABLE") {
                    assertIs<ProjectAccessDeniedException>(error)
                } else {
                    assertIs<ConversationPinOperationFailedException>(error)
                }
                assertFalse(error.toString().contains("PRIVATE_"))
                assertNull(error.cause); assertTrue(error.suppressed.isEmpty())
            }
            assertEquals(cases.size, calls.size)
            assertTrue(calls.all { it is CreateConversationWithCopiedPinsRequest })
            assertFalse(calls.any { it is CreateConversationRequest })
        }
    }

    @Test fun `copy only RPC forwards the full request and returns the new conversation`() {
        val project = Project.Id("pin-copy-project")
        val participants = setOf(Conversation.Participant.User(User.Id("pin-copy-user")),
            Conversation.Participant.Agent(AgentDefinition.Id("pin-copy-default-agent")))
        val created = Conversation(Conversation.Id("pin-copy-created"), project, participants,
            currentThread = Conversation.Thread.Id("pin-copy-thread"),
            createdAt = Instant.fromEpochMilliseconds(1), updatedAt = Instant.fromEpochMilliseconds(1))
        var calls = 0
        withClient({ request ->
            calls++
            assertEquals(CreateConversationWithCopiedPinsRequest(project, participants, cid, "  new name  "), request)
            ConversationResponse(created)
        }) { client ->
            assertEquals(created, RemoteConversationService(client).createWithCopiedPins(project, participants, cid, "  new name  "))
            assertEquals(1, calls)
        }
    }

    @Test fun `copy only RPC preserves caller cancellation without fallback or retry`() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        withClient({ request ->
            assertIs<CreateConversationWithCopiedPinsRequest>(request)
            calls++
            entered.complete(Unit)
            release.await()
            ConversationResponse(null)
        }) { client ->
            coroutineScope {
                val cancelled = CancellationException("Synthetic pin copy cancellation")
                val boundaryFailure = CompletableDeferred<Throwable>()
                val call = async {
                    try {
                        RemoteConversationService(client).createWithCopiedPins(Project.Id("project"), emptySet(), cid, "")
                    } catch (error: Throwable) {
                        boundaryFailure.complete(error)
                        throw error
                    }
                }
                try {
                    entered.await()
                    call.cancel(cancelled)
                    // Inspect the service failure itself, not only the already-cancelled Job.
                    // Coroutine stack-trace recovery can copy CancellationException at suspend
                    // boundaries, retaining the exact original as its cause.
                    val chain = generateSequence(boundaryFailure.await()) { it.cause }.take(8).toList()
                    assertTrue(chain.all { it is CancellationException && it.message == cancelled.message })
                    assertSame(cancelled, chain.last())
                    assertFailsWith<CancellationException> { call.await() }
                    assertEquals(1, calls)
                } finally {
                    release.complete(Unit)
                }
            }
        }
    }

    @Test fun `reopened observation reloads the catalog using the exact conversation revision scope`() = testApplication {
        var stored = emptyList<ConversationPin>()
        var generation = 0L
        val queries = mutableListOf<RemoteStateSyncQuery>()
        var loads = 0
        application {
            install(WebSockets)
            routing { webSocket("/ws") {
                for (frame in incoming) {
                    val envelope = decode(frame) ?: continue
                    val response: ServerPayload? = when (val request = envelope.payload) {
                        is ObserveStateSyncCommand -> {
                            queries += request.query
                            StateSyncInvalidatedEvent(request.subscriptionId, request.query, RemoteStateSyncCursor("pin-source", 1, ++generation))
                        }
                        is PullStateSyncRequest -> StateSyncSnapshotResponse(request.query, request.invalidationCursor, DeclarativeStateRevisionPayload)
                        is ListConversationPinsRequest -> {
                            assertEquals(cid, request.conversationId); loads++; ConversationPinsResponse(stored)
                        }
                        else -> null
                    }
                    if (response != null) send(Frame.Binary(true, RemoteProtocolCodec.encodeServerBinary(GromozekaServerEnvelope(envelope.id, response))))
                }
            } }
        }
        val owner = SupervisorJob()
        val client = GromozekaWsClient("ws://localhost/ws", httpClient = createClient { install(ClientWebSockets) },
            scope = CoroutineScope(owner + Dispatchers.Unconfined), clientInstanceId = ClientInstanceId("pin-observer"), clientPlatform = RemoteClientPlatform.DESKTOP)
        try {
            withTimeout(10_000) {
                val service = RemoteConversationPinService(client)
                assertTrue(service.observe(cid).first().isEmpty())
                stored = listOf(pin(" updated while closed ", 4))
                assertEquals(stored, service.observe(cid).first())
                assertTrue(loads >= 2)
                assertTrue(queries.isNotEmpty())
                assertTrue(queries.all { it == DeclarativeStateRevisionQuery(RemoteDeclarativeStateResource.CONVERSATION_PINS, cid.value) })
            }
        } finally { client.close(); owner.cancelAndJoin() }
    }

    private fun withClient(respond: suspend (ClientRequest) -> ServerResponse, body: suspend (GromozekaWsClient) -> Unit) = testApplication {
        application {
            install(WebSockets)
            routing { webSocket("/ws") {
                for (frame in incoming) {
                    val envelope = decode(frame) ?: continue
                    val request = envelope.payload as? ClientRequest ?: continue
                    val response = respond(request)
                    send(Frame.Binary(true, RemoteProtocolCodec.encodeServerBinary(GromozekaServerEnvelope(envelope.id, response))))
                }
            } }
        }
        val owner = SupervisorJob()
        val client = GromozekaWsClient("ws://localhost/ws", httpClient = createClient { install(ClientWebSockets) },
            scope = CoroutineScope(owner + Dispatchers.Unconfined), clientInstanceId = ClientInstanceId("pin-rpc-test"), clientPlatform = RemoteClientPlatform.DESKTOP)
        try { withTimeout(10_000) { body(client) } }
        finally { client.close(); owner.cancelAndJoin() }
    }

    private fun decode(frame: Frame): GromozekaClientEnvelope? = when (frame) {
        is Frame.Binary -> RemoteProtocolCodec.decodeClientBinary(frame.readBytes())
        is Frame.Text -> RemoteProtocolCodec.decodeClientText(frame.readText())
        else -> null
    }
}
