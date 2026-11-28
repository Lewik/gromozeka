package com.gromozeka.remote.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class LiveInterpreterManualControlProtocolTest {
    @Test
    fun `manual Live selection and commit survive JSON and binary encodings`() {
        for (payload in listOf<ClientPayload>(
            StartLiveInterpreterRequest("manual-session", manualTurnControl = true),
            CommitLiveInterpreterCommand("manual-session"),
        )) {
            val envelope = GromozekaClientEnvelope("manual-request", payload)
            assertEquals(envelope, RemoteProtocolCodec.decodeClientText(RemoteProtocolCodec.encodeClientText(envelope)))
            assertEquals(envelope, RemoteProtocolCodec.decodeClientBinary(RemoteProtocolCodec.encodeClientBinary(envelope)))
        }
    }

    @Test
    fun `normal Live start retains automatic turn control by default`() {
        val envelope = GromozekaClientEnvelope("automatic-request", StartLiveInterpreterRequest("automatic-session"))
        val decoded = RemoteProtocolCodec.decodeClientText(RemoteProtocolCodec.encodeClientText(envelope))
        assertFalse((decoded.payload as StartLiveInterpreterRequest).manualTurnControl)
    }

    @Test
    fun `manual commit rejects invalid session bindings`() {
        for (id in listOf("", "has spaces", "x".repeat(129))) {
            assertFailsWith<IllegalArgumentException> { CommitLiveInterpreterCommand(id) }
        }
    }
}
