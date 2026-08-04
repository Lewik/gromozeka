package com.gromozeka.infrastructure.ai.tool.worker

import com.gromozeka.domain.service.ComputerUseDisplayId
import com.gromozeka.domain.service.ComputerUseObservationId
import com.gromozeka.domain.service.ComputerUseObservationReference
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import kotlinx.datetime.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ComputerUseObservationReferenceCodecTest {
    @Test
    fun `round trips a signed observation reference`() {
        val encoded = ComputerUseObservationReferenceCodec.encode(Reference)

        assertEquals(Reference, ComputerUseObservationReferenceCodec.decode(encoded))
    }

    @Test
    fun `rejects modified observation geometry`() {
        val encoded = ComputerUseObservationReferenceCodec.encode(Reference)
        val parts = encoded.split('.')
        val decoder = Base64.getUrlDecoder()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val originalPayload = decoder.decode(parts[0]).decodeToString()
        val modifiedPayload = originalPayload.replace("\"imageWidth\":100", "\"imageWidth\":999")
        assertNotEquals(originalPayload, modifiedPayload)

        assertFailsWith<IllegalArgumentException> {
            ComputerUseObservationReferenceCodec.decode(
                "${encoder.encodeToString(modifiedPayload.encodeToByteArray())}.${parts[1]}"
            )
        }
    }

    private companion object {
        val Reference = ComputerUseObservationReference(
            id = ComputerUseObservationId("observation-1"),
            workerId = ConversationRuntimeWorkerId("worker-1"),
            workerSessionId = ConversationRuntimeWorkerSessionId("session-1"),
            displayId = ComputerUseDisplayId("display-1"),
            imageWidth = 100,
            imageHeight = 60,
            logicalOriginX = 0,
            logicalOriginY = 0,
            logicalWidth = 100,
            logicalHeight = 60,
            capturedAt = Instant.parse("2026-08-04T00:00:00Z"),
        )
    }
}
