package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.MessageDelivery
import kotlin.test.*

class MessageDeliveryPreferenceProtocolTest {
    @Test fun preferencesRoundTripWithoutClientSuppliedUserIdentity() {
        for (request in listOf<ClientRequest>(GetMessageDeliveryModeRequest) + MessageDelivery.entries.map { SetMessageDeliveryModeRequest(it) }) {
            val envelope = GromozekaClientEnvelope("request", request)
            assertEquals(envelope, RemoteProtocolCodec.decodeClientText(RemoteProtocolCodec.encodeClientText(envelope)))
            assertEquals(envelope, RemoteProtocolCodec.decodeClientBinary(RemoteProtocolCodec.encodeClientBinary(envelope)))
        }
        for (mode in MessageDelivery.entries) {
            val envelope = GromozekaServerEnvelope("request", MessageDeliveryModeResponse(mode))
            assertEquals(envelope, RemoteProtocolCodec.decodeServerText(RemoteProtocolCodec.encodeServerText(envelope)))
            assertEquals(envelope, RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(envelope)))
        }
    }
}
