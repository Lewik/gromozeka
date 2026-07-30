package com.gromozeka.remote.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerGatewayProtocolTest {
    @Test
    fun `gateway messages round trip through binary codec`() {
        val expected = WorkerGatewayMessage.Failure(
            code = "WORKER_SESSION_CONFLICT",
            message = "Worker already has an active session",
        )

        assertEquals(expected, WorkerGatewayCodec.decode(WorkerGatewayCodec.encode(expected)))
    }
}
