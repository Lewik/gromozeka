package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import kotlin.test.*
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
}
