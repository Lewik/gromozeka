package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import kotlin.test.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

class SlotProtocolTest {
    @Test fun slotRequestsAndHumanConfirmationRoundTrip() {
        listOf<ClientRequest>(ListSlotsRequest, PrepareSlotReclaimRequest("lease"), ConfirmSlotReclaimRequest("lease", "receipt"), CancelSlotRequest("request")).forEach {
            val envelope = GromozekaClientEnvelope("call", it)
            assertEquals(envelope, RemoteProtocolCodec.decodeClientBinary(RemoteProtocolCodec.encodeClientBinary(envelope)))
            assertEquals(envelope, RemoteProtocolCodec.decodeClientText(RemoteProtocolCodec.encodeClientText(envelope)))
        }
    }
    @Test fun slotCatalogRoundTripsWithoutChangingLeaseIdentity() {
        val time = Instant.fromEpochMilliseconds(0)
        val slot = Slot("slot", 7, User.Id("user"), Project.Id("project"), Workspace.Id("workspace"), WorkspaceMount.Id("mount"), createdAt = time)
        val lease = SlotLease("lease", 7, Conversation.Id("conversation"), slot.userId, AgentDefinition.Id("agent"), SlotAccess.WRITE, time)
        val view = SlotView(SlotSnapshot(slot, listOf(lease), emptyList()), "worker", "/clone", mapOf("conversation" to "Conversation"))
        val response = GromozekaServerEnvelope("result", SlotsResponse(listOf(view)))
        assertEquals(response, RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(response)))
        assertEquals(response, RemoteProtocolCodec.decodeServerText(RemoteProtocolCodec.encodeServerText(response)))
    }
    @Test fun automaticWaitAndGrantRoundTripWithoutInventingTarget() {
        val request = SlotRequest("automatic", "key", null, Conversation.Id("conversation"), User.Id("user"), AgentDefinition.Id("agent"), SlotAccess.READ,
            Instant.fromEpochMilliseconds(0), projectId = Project.Id("project"))
        val granted = request.copy(state = SlotRequest.State.GRANTED, leaseId = "lease", grantedSlotNumber = 7)
        listOf<ServerResponse>(
            SlotsResponse(emptyList(), listOf(request), mapOf("conversation" to "Conversation")),
            SlotRequestResponse(granted), SlotRequestResponse(request.copy(state = SlotRequest.State.CANCELLED)),
        ).forEach { payload ->
            val response = GromozekaServerEnvelope("result", payload)
            assertEquals(response, RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(response)))
            assertEquals(response, RemoteProtocolCodec.decodeServerText(RemoteProtocolCodec.encodeServerText(response)))
        }
        val state = SlotsStatePayload(emptyList(), listOf(request), mapOf("conversation" to "Conversation"))
        assertEquals(state, Json.decodeFromString<SlotsStatePayload>(Json.encodeToString(SlotsStatePayload.serializer(), state)))
    }

    @Test fun legacyCatalogAndExactRequestsDecodeWithDefaults() {
        val response = Json.decodeFromString<SlotsResponse>("""{"slots":[]}""")
        assertTrue(response.automaticRequests.isEmpty()); assertTrue(response.conversationTitles.isEmpty())
        assertEquals(SlotsStatePayload(emptyList()), Json.decodeFromString<SlotsStatePayload>("""{"slots":[]}"""))
        val request = SlotRequest("exact", "key", 7, Conversation.Id("conversation"), User.Id("user"), AgentDefinition.Id("agent"), SlotAccess.WRITE, Instant.fromEpochMilliseconds(0))
        val encoded = Json.encodeToJsonElement(SlotRequest.serializer(), request).jsonObject
        val legacy = JsonObject(encoded - "projectId" - "grantedSlotNumber")
        assertEquals(request, Json.decodeFromJsonElement<SlotRequest>(legacy))
    }

}
