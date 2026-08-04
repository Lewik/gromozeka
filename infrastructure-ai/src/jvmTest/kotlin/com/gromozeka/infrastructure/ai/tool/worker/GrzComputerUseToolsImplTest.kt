package com.gromozeka.infrastructure.ai.tool.worker

import com.gromozeka.domain.service.ComputerUseAction
import com.gromozeka.domain.service.ComputerUseController
import com.gromozeka.domain.service.ComputerUseDisplay
import com.gromozeka.domain.service.ComputerUseDisplayId
import com.gromozeka.domain.service.ComputerUseObservation
import com.gromozeka.domain.service.ComputerUseObservationId
import com.gromozeka.domain.service.ComputerUseObservationReference
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.infrastructure.ai.config.TypedToolCallbackAdapter
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GrzComputerUseToolsImplTest {
    @Test
    fun `typed adapter maps nested actions and preserves screenshot result`() {
        val controller = FakeComputerUseController()
        val callback = TypedToolCallbackAdapter().adapt(GrzComputerActToolImpl(controller))
        val observationRef = ComputerUseObservationReferenceCodec.encode(Reference)

        val result = callback.callResult(
            """{
                "observation_ref":"$observationRef",
                "actions":[{"kind":"CLICK","x":25,"y":30,"click_count":2}],
                "max_long_edge":1568
            }""".trimIndent()
        )

        val click = assertIs<ComputerUseAction.Click>(controller.actions.single())
        assertEquals(25, click.point?.x)
        assertEquals(30, click.point?.y)
        assertEquals(2, click.count)
        assertIs<AiToolResult.Text>(result[0])
        assertIs<AiToolResult.Binary>(result[1])
        assertTrue(callback.definition.inputSchema.contains("\"actions\""))
        assertTrue(callback.definition.inputSchema.contains("\"CLICK\""))
    }

    private class FakeComputerUseController : ComputerUseController {
        override val available = true
        override val unavailableReason: String? = null
        var actions: List<ComputerUseAction> = emptyList()

        override suspend fun targets(): List<ComputerUseDisplay> = emptyList()

        override suspend fun observe(displayId: ComputerUseDisplayId, maxLongEdge: Int): ComputerUseObservation =
            error("Unused")

        override suspend fun act(
            observation: ComputerUseObservationReference,
            actions: List<ComputerUseAction>,
            maxLongEdge: Int,
            cancellationCheck: () -> Unit,
        ): ComputerUseObservation {
            cancellationCheck()
            this.actions = actions
            return ComputerUseObservation(
                reference = observation.copy(
                    id = ComputerUseObservationId("observation-after"),
                    capturedAt = Instant.parse("2026-08-04T00:00:01Z"),
                ),
                png = byteArrayOf(1, 2, 3),
            )
        }
    }

    private companion object {
        val Reference = ComputerUseObservationReference(
            id = ComputerUseObservationId("observation-before"),
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
