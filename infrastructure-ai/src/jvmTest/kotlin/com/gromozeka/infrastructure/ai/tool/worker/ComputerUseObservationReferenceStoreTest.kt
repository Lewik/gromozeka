package com.gromozeka.infrastructure.ai.tool.worker

import com.gromozeka.domain.service.ComputerUseDisplayId
import com.gromozeka.domain.service.ComputerUseObservationId
import com.gromozeka.domain.service.ComputerUseObservationReference
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ComputerUseObservationReferenceStoreTest {
    @Test
    fun `returns a short opaque reference and consumes it once`() {
        val store = ComputerUseObservationReferenceStore()
        val encoded = store.register(Reference)

        assertTrue(encoded.length < 32)
        assertEquals(Reference, store.consume(encoded))
        assertFailsWith<IllegalArgumentException> { store.consume(encoded) }
    }

    @Test
    fun `rejects a modified opaque reference`() {
        val store = ComputerUseObservationReferenceStore()
        val encoded = store.register(Reference)
        val modified = encoded.dropLast(1) + if (encoded.last() == 'A') 'B' else 'A'
        assertNotEquals(encoded, modified)

        assertFailsWith<IllegalArgumentException> { store.consume(modified) }
        assertEquals(Reference, store.consume(encoded))
    }

    @Test
    fun `expires old references`() {
        var now = 0L
        val store = ComputerUseObservationReferenceStore.forTesting(
            ttlNanos = 10,
            nanoTime = { now },
        )
        val encoded = store.register(Reference)

        now = 10

        assertFailsWith<IllegalArgumentException> { store.consume(encoded) }
    }

    @Test
    fun `evicts the oldest reference when bounded capacity is reached`() {
        val store = ComputerUseObservationReferenceStore.forTesting(maxEntries = 1)
        val first = store.register(Reference)
        val secondReference = Reference.copy(id = ComputerUseObservationId("observation-2"))
        val second = store.register(secondReference)

        assertFailsWith<IllegalArgumentException> { store.consume(first) }
        assertEquals(secondReference, store.consume(second))
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
