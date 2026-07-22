package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryReadTrace
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryNamespace
import kotlinx.datetime.Instant
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
        val usageGuidance = root.getValue("usage_guidance").jsonPrimitive.content
        assertTrue(usageGuidance.contains("asynchronously"))
        assertTrue(usageGuidance.contains("not evidence"))
        assertTrue(usageGuidance.contains("make no claim about source access or absence"))
        val memoryContext = root.getValue("memory_context").jsonPrimitive.content
        assertTrue(memoryContext.contains("follow-up memory result"))
        assertTrue("not available" !in memoryContext.lowercase())
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

    @Test
    fun enrichContextUsesBoundedEvidencePacketInsteadOfInternalPrompt() {
        val sourceText = buildString {
            repeat(40) { index ->
                appendLine("Section $index contains ordinary background material. ${"details ".repeat(120)}")
            }
            appendLine("The selected answer marker is cobalt-seven.")
        }
        val source = MemorySource.ExternalRecord(
            id = MemorySource.Id("source-large"),
            namespace = MemoryNamespace.Global,
            recordRef = "test:source-large",
            contentText = sourceText,
            contentHash = "source-large",
            observedAt = Instant.parse("2026-07-22T00:00:00Z"),
            createdAt = Instant.parse("2026-07-22T00:00:00Z"),
        )
        val result = MemoryReadResult(
            plan = MemoryReadPlan(),
            retrievedHits = listOf(MemoryStore.SearchHit.SourceHit(source, score = 1.0)),
            runtimePrompt = "internal prompt must not leak " + "x".repeat(100_000),
            trace = MemoryReadTrace(targetText = "selected answer marker cobalt-seven"),
        )

        val root = Json.parseToJsonElement(MemoryToolResultRenderer.enrichContextResultJsonString(result)).jsonObject
        val memoryContext = root.getValue("memory_context").jsonPrimitive.content

        assertTrue(memoryContext.length <= MAX_MEMORY_TOOL_CONTEXT_CHARS)
        assertTrue(memoryContext.contains("cobalt-seven"))
        assertTrue(!memoryContext.contains("internal prompt must not leak"))
        assertEquals(memoryContext.length.toString(), root.getValue("memory_context_chars").jsonPrimitive.content)
        assertTrue(root.toString().length < 40_000)
    }

    @Test
    fun answerQuestionResultDoesNotRepeatInternalMemoryContext() {
        val readResult = MemoryReadResult(
            plan = MemoryReadPlan(),
            retrievedHits = emptyList(),
            runtimePrompt = "x".repeat(100_000),
        )
        val result = MemoryQuestionAnswerResult(
            readResult = readResult,
            answer = "Four.",
            reasoning = "The selected memory supports four.",
            sufficiency = MemoryQuestionAnswerResult.Sufficiency.ANSWERED,
            evidenceRefs = listOf("CLAIM:four"),
            countedItems = emptyList(),
            excludedRefs = emptyList(),
        )

        val root = Json.parseToJsonElement(MemoryToolResultRenderer.answerQuestionResultJsonString(result)).jsonObject

        assertEquals("Four.", root.getValue("answer").jsonPrimitive.content)
        assertTrue("memory_context" !in root)
        assertTrue(root.toString().length < 2_000)
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
