package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.AiRequestActivitySnapshot
import com.gromozeka.domain.service.AiRequestTaskWait
import com.gromozeka.domain.service.AiRequestWaitReason
import com.gromozeka.domain.service.ConversationRuntimeTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiRequestActivityProtocolTest {
    @Test
    fun aggregateAndConversationWaitsRoundTripThroughBothCodecs() {
        val query = AiRequestActivityStateQuery(Conversation.Id("conversation"))
        val cursor = RemoteStateSyncCursor("server", 1, 2)
        for (payload in listOf<ClientPayload>(ObserveStateSyncCommand("watch", query), PullStateSyncRequest(query, cursor))) {
            val envelope = GromozekaClientEnvelope("request", payload)
            assertEquals(envelope, RemoteProtocolCodec.decodeClientText(RemoteProtocolCodec.encodeClientText(envelope)))
            assertEquals(envelope, RemoteProtocolCodec.decodeClientBinary(RemoteProtocolCodec.encodeClientBinary(envelope)))
        }
        val snapshot = AiRequestActivitySnapshot(3, 2, 1, listOf(
            AiRequestTaskWait(ConversationRuntimeTask.Id("task"), AiRequestWaitReason.TPM)))
        val envelope = GromozekaServerEnvelope("request", StateSyncSnapshotResponse(query, cursor, AiRequestActivityStatePayload(snapshot)))
        assertEquals(envelope, RemoteProtocolCodec.decodeServerText(RemoteProtocolCodec.encodeServerText(envelope)))
        assertEquals(envelope, RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(envelope)))
        assertFailsWith<IllegalArgumentException> { snapshot.copy(runningRequests = -1) }
        assertFailsWith<IllegalArgumentException> { snapshot.copy(taskWaits = snapshot.taskWaits + snapshot.taskWaits) }
    }
}
