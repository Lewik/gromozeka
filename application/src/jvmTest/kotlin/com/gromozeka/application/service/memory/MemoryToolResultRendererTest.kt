package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryReadTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MemoryToolResultRendererTest {
    @Test
    fun pendingEnrichContextResultIsExplicitlyPending() {
        val root = Json.parseToJsonElement(MemoryToolResultRenderer.pendingEnrichContextResultJsonString()).jsonObject

        assertEquals("pending", root.getValue("status").jsonPrimitive.content)
        assertEquals("ASYNC_RECALL", root.getValue("context_mode").jsonPrimitive.content)
        assertTrue(root.getValue("usage_guidance").jsonPrimitive.content.contains("asynchronously"))
    }

    @Test
    fun enrichContextSelectedRefsFollowSelectorRankBeforeTraceHitOrder() {
        val lowRankedHit = traceHit(id = "claim-a", summary = "Lower-ranked selected claim")
        val highRankedHit = traceHit(id = "claim-b", summary = "Higher-ranked selected claim")
        val result = MemoryReadResult(
            plan = MemoryReadPlan(),
            retrievedHits = emptyList(),
            runtimePrompt = "memory context",
            trace = MemoryReadTrace(
                selectedHits = listOf(lowRankedHit, highRankedHit),
                selectorDecisions = listOf(
                    selectorDecision(highRankedHit.ref, rank = 1, reason = "direct answer"),
                    selectorDecision(lowRankedHit.ref, rank = 2, reason = "secondary evidence"),
                ),
            ),
        )

        val root = Json.parseToJsonElement(MemoryToolResultRenderer.enrichContextResultJsonString(result)).jsonObject
        val selectedRefs = root.getValue("selected_refs").jsonArray

        assertEquals("claim-b", selectedRefs[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("direct answer", selectedRefs[0].jsonObject.getValue("selection_reason").jsonPrimitive.content)
        assertEquals("claim-a", selectedRefs[1].jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun selectedRefsDoNotExposeRejectedDecisionsAsSelectionReasons() {
        val selectedBySafetyHit = traceHit(id = "claim-safety", summary = "Safety-selected claim")
        val result = MemoryReadResult(
            plan = MemoryReadPlan(),
            retrievedHits = emptyList(),
            runtimePrompt = "memory context",
            trace = MemoryReadTrace(
                selectedHits = listOf(selectedBySafetyHit),
                selectorDecisions = listOf(
                    MemoryReadTrace.SelectorDecision(
                        ref = selectedBySafetyHit.ref,
                        selected = false,
                        rank = Int.MAX_VALUE,
                        reason = "rejected as less direct",
                    )
                ),
            ),
        )

        val root = Json.parseToJsonElement(MemoryToolResultRenderer.enrichContextResultJsonString(result)).jsonObject
        val selectedRef = root.getValue("selected_refs").jsonArray.single().jsonObject

        assertEquals("claim-safety", selectedRef.getValue("id").jsonPrimitive.content)
        assertTrue("selection_reason" !in selectedRef)
    }

    private fun traceHit(
        id: String,
        summary: String,
    ): MemoryReadTrace.Hit =
        MemoryReadTrace.Hit(
            ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, id),
            score = 0.5,
            summary = summary,
            predicate = "owns",
            status = "ACTIVE",
        )

    private fun selectorDecision(
        ref: MemoryItemRef,
        rank: Int,
        reason: String,
    ): MemoryReadTrace.SelectorDecision =
        MemoryReadTrace.SelectorDecision(
            ref = ref,
            selected = true,
            rank = rank,
            reason = reason,
        )
}
