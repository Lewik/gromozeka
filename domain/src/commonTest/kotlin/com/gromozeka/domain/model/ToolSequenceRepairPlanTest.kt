package com.gromozeka.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolSequenceRepairPlanTest {
    @Test
    fun `repair body plan includes an adjacent call group`() {
        fun header(
            position: Int,
            id: String,
            role: Conversation.Message.Role,
            callIds: List<String> = emptyList(),
            resultIds: List<String> = emptyList(),
            requiresRepair: Boolean,
        ) = ToolSequenceMessageHeader(
            position = position,
            id = Conversation.Message.Id(id),
            role = role,
            facts = ToolSequenceFacts(
                callIds = callIds,
                resultIds = resultIds,
                providerManaged = false,
                toolResultsOnly = resultIds.isNotEmpty() && callIds.isEmpty(),
                fullCompactionAnchor = false,
            ),
            requiresRepair = requiresRepair,
        )

        val headers = listOf(
            header(0, "first-call", Conversation.Message.Role.ASSISTANT,
                callIds = listOf("first"), requiresRepair = true),
            header(1, "second-call", Conversation.Message.Role.ASSISTANT,
                callIds = listOf("second"), requiresRepair = false),
            header(2, "combined-result", Conversation.Message.Role.USER,
                resultIds = listOf("first", "second"), requiresRepair = true),
        )

        assertEquals(
            headers.map(ToolSequenceMessageHeader::id),
            toolSequenceRepairBodyIds(headers),
        )
    }
}
