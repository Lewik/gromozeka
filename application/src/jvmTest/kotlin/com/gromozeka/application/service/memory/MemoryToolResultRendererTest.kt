package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryReadTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MemoryToolResultRendererTest {
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
