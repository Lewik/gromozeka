package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*
import kotlin.time.Instant

class ConversationPinProtocolTest {
    private val owner = Conversation.Id("pin-owner")
    private val id = ConversationPin.Id("pin-id")
    private val source = ConversationPinSource(Conversation.Id("old-source"), Conversation.Message.Id("old-message"))
    private val text = "  שלום\r\nКириллица 👩🏽‍💻 100%\t "

    @Test fun allPinRequestsRoundTripThroughBothCodecs() {
        val requests = listOf<ClientRequest>(
            ListConversationPinsRequest(owner), GetConversationPinRequest(owner, id),
            CreateConversationPinRequest(owner, text, source, "stable-retry-key"),
            UpdateConversationPinRequest(owner, id, 7, text), DeleteConversationPinRequest(owner, id, 7),
            PullStateSyncRequest(DeclarativeStateRevisionQuery(RemoteDeclarativeStateResource.CONVERSATION_PINS, owner.value),
                RemoteStateSyncCursor("source", 1, 2)),
        )
        for ((index, request) in requests.withIndex()) {
            val envelope = GromozekaClientEnvelope("request-$index", request)
            assertEquals(envelope, RemoteProtocolCodec.decodeClientText(RemoteProtocolCodec.encodeClientText(envelope)))
            assertEquals(envelope, RemoteProtocolCodec.decodeClientBinary(RemoteProtocolCodec.encodeClientBinary(envelope)))
        }
    }

    @Test fun literalTextAndProvenanceSurvivePinResponses() {
        val pin = ConversationPin(id, owner, text, 4, 7, Instant.fromEpochMilliseconds(123), Instant.fromEpochMilliseconds(456), source)
        for (response in listOf<ServerResponse>(ConversationPinsResponse(listOf(pin)), ConversationPinResponse(pin), ConversationPinResponse(null))) {
            val envelope = GromozekaServerEnvelope("response", response)
            assertEquals(envelope, RemoteProtocolCodec.decodeServerText(RemoteProtocolCodec.encodeServerText(envelope)))
            assertEquals(envelope, RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(envelope)))
        }
    }

    @Test fun updatesExposeOnlyTheTextMutationAndCasIdentity() {
        val encoded = RemoteProtocolCodec.encodeClientText(GromozekaClientEnvelope("update", UpdateConversationPinRequest(owner, id, 3, text)))
        val payload = Json.parseToJsonElement(encoded).jsonObject.getValue("payload").jsonObject
        assertTrue(payload.containsKey("text")); assertTrue(payload.containsKey("expectedRevision"))
        for (key in listOf("actor", "actorId", "agentId", "title", "ordinal", "source", "createdAt", "creationKey")) {
            assertFalse(payload.containsKey(key), "Unexpected editable metadata: $key")
        }
    }

    @Test fun pinPreservingCreateIsADistinctOperationRatherThanAnIgnoredCreateOption() {
        val request = CreateConversationWithCopiedPinsRequest(Project.Id("project"),
            setOf(Conversation.Participant.User(User.Id("user"))), owner)
        val envelope = GromozekaClientEnvelope("copy", request)
        assertEquals(envelope, RemoteProtocolCodec.decodeClientText(RemoteProtocolCodec.encodeClientText(envelope)))
        assertEquals(envelope, RemoteProtocolCodec.decodeClientBinary(RemoteProtocolCodec.encodeClientBinary(envelope)))
        val ordinary = GromozekaClientEnvelope("ordinary", CreateConversationRequest(request.projectId, request.participants))
        assertFalse(RemoteProtocolCodec.encodeClientText(ordinary).contains("sourceConversationId"))
        assertTrue(RemoteProtocolCodec.encodeClientText(envelope).contains("create_conversation_with_copied_pins"))
    }
}
