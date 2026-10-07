package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.visual.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class VisualProtocolTest {
    private val state = Json.parseToJsonElement("""{"form":{"text":"שלום 😀","flag":true,"value":null},"data":{"rows":[1,"2",false,null]}}""").jsonObject
    private val conversation = Conversation.Id("conversation-1")

    @Test fun visualActionsRoundTripThroughJsonAndCbor() {
        val action = VisualAction("event-12345", "visual-1", 2, "save", state)
        val request = GromozekaClientEnvelope("request-1", VisualActionRequest(conversation, action))
        assertEquals(request, RemoteProtocolCodec.decodeClientBinary(RemoteProtocolCodec.encodeClientBinary(request)))
        assertEquals(request, RemoteProtocolCodec.decodeClientText(RemoteProtocolCodec.encodeClientText(request)))
    }

    @Test fun visualSnapshotsRoundTripWithoutChangingJsonTypes() {
        val time = Instant.fromEpochMilliseconds(0)
        val visual = Visual("visual-1",conversation,User.Id("user-1"),document="<html/>",title="Visual",state=state,createdAt=time,updatedAt=time)
        val response = GromozekaServerEnvelope("response-1",VisualsResponse(listOf(visual)))
        assertEquals(response,RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(response)))
        assertEquals(response,RemoteProtocolCodec.decodeServerText(RemoteProtocolCodec.encodeServerText(response)))
    }

    @Test fun oneShotHighlightsRoundTripAsDirectivesNotVisualSnapshots() {
        val command = VisualHighlightCommand("highlight-1", conversation, "visual-1", 2, listOf("query", "save"))
        val envelope = GromozekaServerEnvelope("directive-1", HighlightVisualDirective(command))
        assertEquals(envelope, RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(envelope)))
        assertEquals(envelope, RemoteProtocolCodec.decodeServerText(RemoteProtocolCodec.encodeServerText(envelope)))
    }

    @Test fun visualInteractionMessageKeepsTypedSnapshotThroughJsonAndCbor() {
        val event = Conversation.Message.ContentItem.VisualInteraction("visual-1", "Panel", 2, "event-12345", "go", "Go", state)
        val message = Conversation.Message(Conversation.Message.Id("click"), conversation,
            role = Conversation.Message.Role.USER, content = listOf(event), createdAt = Instant.fromEpochMilliseconds(0))
        val envelope = GromozekaServerEnvelope("message-1", MessagesResponse(listOf(message)))
        assertEquals(envelope, RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(envelope)))
        assertEquals(envelope, RemoteProtocolCodec.decodeServerText(RemoteProtocolCodec.encodeServerText(envelope)))
    }

    @Test fun completeSectionUpdatesRoundTripThroughJsonAndCbor() {
        val sections = Json.parseToJsonElement("""{"data":{"nested":{"new":true},"rows":[]}}""").jsonObject
        val request = GromozekaClientEnvelope("section-update", UpdateVisualRequest(conversation, "visual-1", VisualUpdate(state = sections)))
        assertEquals(request, RemoteProtocolCodec.decodeClientBinary(RemoteProtocolCodec.encodeClientBinary(request)))
        assertEquals(request, RemoteProtocolCodec.decodeClientText(RemoteProtocolCodec.encodeClientText(request)))
    }

    @Test fun nullableHandlerMutationPreservesIntent() {
        val request = GromozekaClientEnvelope("request-2",UpdateVisualRequest(conversation,"visual-1",VisualUpdate(updateHandler=true,handler=null)))
        assertEquals(request,RemoteProtocolCodec.decodeClientBinary(RemoteProtocolCodec.encodeClientBinary(request)))
    }
}
