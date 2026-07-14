package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryIngestPlan
import com.gromozeka.domain.model.memory.MemoryIngestPlanner
import com.gromozeka.domain.model.memory.MemoryIngestPlanningRequest
import com.gromozeka.domain.model.memory.MemoryIngestSectionPlan
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryIngestPreflightEngineTest {
    private val engine = MemoryIngestPreflightEngine()

    @Test
    fun readyPlanPacksExactContiguousSourceSectionsIntoOneProcessingChunk() = runBlocking {
        val source = """
            # First

            Alpha paragraph.

            # Second

            Beta paragraph.
        """.trimIndent()
        val result = engine.inspect(
            contentText = source,
            sourceLabel = "test.md",
            planner = FixedPlanner { request ->
                assertEquals(listOf("b1", "b2", "b3", "b4"), request.blocks.map { it.id })
                MemoryIngestPlan(
                    decision = MemoryIngestPlan.Decision.READY,
                    sections = listOf(
                        MemoryIngestSectionPlan("First", listOf("b1", "b2")),
                        MemoryIngestSectionPlan("Second", listOf("b3", "b4")),
                    ),
                    reason = "Existing headings and paragraphs provide an unambiguous structure.",
                )
            },
        )

        assertEquals(2, result.plan.sections.size)
        assertEquals(1, result.sections.size)
        assertEquals("# First\n\nAlpha paragraph.\n\n# Second\n\nBeta paragraph.", result.sections.single().text)
        assertEquals(listOf("First .. Second"), result.sections.single().headingPath)
        assertEquals(1, result.sections.single().startLine)
        assertEquals(7, result.sections.single().endLine)
    }

    @Test
    fun preflightPreservesSourceLineWhitespace() = runBlocking {
        val source = "\n  Alpha.  \n\n\tBeta.\t\n"
        val result = engine.inspect(
            contentText = source,
            sourceLabel = "exact text",
            planner = FixedPlanner { request ->
                assertEquals(listOf("  Alpha.  ", "\tBeta.\t"), request.blocks.map { it.text })
                MemoryIngestPlan(
                    decision = MemoryIngestPlan.Decision.READY,
                    sections = listOf(
                        MemoryIngestSectionPlan("Alpha", listOf("b1")),
                        MemoryIngestSectionPlan("Beta", listOf("b2")),
                    ),
                    reason = "Existing paragraph boundary is unambiguous.",
                )
            },
        )

        assertEquals(listOf("Alpha .. Beta"), result.sections.single().headingPath)
        assertEquals("  Alpha.  \n\n\tBeta.\t", result.sections.single().text)
        assertEquals(2, result.sections.single().startLine)
    }

    @Test
    fun contentIdentityIgnoresOnlyStructuralOuterWhitespace() = runBlocking {
        suspend fun inspect(source: String) = engine.inspect(
            contentText = source,
            sourceLabel = "identity",
            planner = FixedPlanner { request ->
                MemoryIngestPlan(
                    decision = MemoryIngestPlan.Decision.READY,
                    sections = listOf(MemoryIngestSectionPlan("Content", request.blocks.map { it.id })),
                    reason = "One coherent section.",
                )
            },
        )

        val compact = inspect("Alpha.\n\nBeta.")
        val padded = inspect("\nAlpha.\n   \nBeta.\n")

        assertEquals(compact.contentHash, padded.contentHash)
        assertEquals("Alpha.\n   \nBeta.", padded.sections.single().text)
    }

    @Test
    fun proposedStructureRequiresApprovalBeforeItBecomesReady() = runBlocking {
        val source = "Alpha paragraph.\n\nBeta paragraph."
        val proposed = engine.inspect(
            contentText = source,
            sourceLabel = "plain text",
            planner = FixedPlanner {
                MemoryIngestPlan(
                    decision = MemoryIngestPlan.Decision.NEEDS_USER_CONFIRMATION,
                    sections = listOf(
                        MemoryIngestSectionPlan("Alpha", listOf("b1")),
                        MemoryIngestSectionPlan("Beta", listOf("b2")),
                    ),
                    reason = "The grouping is plausible but interpretive.",
                )
            },
        )

        assertEquals(MemoryIngestPlan.Decision.NEEDS_USER_CONFIRMATION, proposed.plan.decision)
        assertEquals(2, proposed.sections.size)
        val approved = engine.approve(source, proposed.plan)
        assertEquals(MemoryIngestPlan.Decision.READY, approved.plan.decision)
        assertEquals(proposed.contentHash, approved.contentHash)
        assertEquals(listOf("Alpha .. Beta"), approved.sections.single().headingPath)
        assertEquals("Alpha paragraph.\n\nBeta paragraph.", approved.sections.single().text)
    }

    @Test
    fun readyPlanPacksAdjacentSectionsGreedilyWithinTechnicalLimit() = runBlocking {
        val first = "a".repeat(3_000)
        val second = "b".repeat(3_000)
        val third = "c".repeat(3_000)
        val result = engine.inspect(
            contentText = "$first\n\n$second\n\n$third",
            sourceLabel = "large structured source",
            planner = FixedPlanner {
                MemoryIngestPlan(
                    decision = MemoryIngestPlan.Decision.READY,
                    sections = listOf(
                        MemoryIngestSectionPlan("First", listOf("b1")),
                        MemoryIngestSectionPlan("Second", listOf("b2")),
                        MemoryIngestSectionPlan("Third", listOf("b3")),
                    ),
                    reason = "Three explicit logical sections.",
                )
            },
        )

        assertEquals(3, result.plan.sections.size)
        assertEquals(2, result.sections.size)
        assertEquals(listOf("First .. Second"), result.sections[0].headingPath)
        assertEquals(listOf("Third"), result.sections[1].headingPath)
        assertTrue(result.sections.all { it.text.length <= MAX_MEMORY_INGEST_SECTION_CHARS })
    }

    @Test
    fun oversizedUnbrokenBlockNeedsUserStructureWithoutCallingPlanner() = runBlocking {
        var called = false
        val result = engine.inspect(
            contentText = "x".repeat(MAX_MEMORY_INGEST_SECTION_CHARS + 1),
            sourceLabel = "oversized",
            planner = FixedPlanner {
                called = true
                error("Planner must not be called for a technically unsegmentable block.")
            },
        )

        assertEquals(MemoryIngestPlan.Decision.NEEDS_USER_STRUCTURE, result.plan.decision)
        assertTrue(result.sections.isEmpty())
        assertTrue(result.plan.reason.contains("no safe blank-line boundary"))
        assertEquals(false, called)
    }

    @Test
    fun plannerCannotDropOrReorderSourceBlocks() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            engine.inspect(
                contentText = "First.\n\nSecond.",
                sourceLabel = "invalid plan",
                planner = FixedPlanner {
                    MemoryIngestPlan(
                        decision = MemoryIngestPlan.Decision.READY,
                        sections = listOf(MemoryIngestSectionPlan("Only second", listOf("b2"))),
                        reason = "Invalid test plan.",
                    )
                },
            )
        }
        Unit
    }

    @Test
    fun confirmationRequiresAnActualSegmentationChoice() {
        assertFailsWith<IllegalArgumentException> {
            MemoryIngestPlan(
                decision = MemoryIngestPlan.Decision.NEEDS_USER_CONFIRMATION,
                sections = listOf(MemoryIngestSectionPlan("Whole source", listOf("b1"))),
                reason = "There is only one possible section.",
            )
        }
    }

    @Test
    fun structureRejectionCannotContainAProposedPlan() {
        assertFailsWith<IllegalArgumentException> {
            MemoryIngestPlan(
                decision = MemoryIngestPlan.Decision.NEEDS_USER_STRUCTURE,
                sections = listOf(MemoryIngestSectionPlan("Unsafe guess", listOf("b1"))),
                reason = "The source cannot be safely structured.",
            )
        }
    }

    @Test
    fun sectionLimitIncludesTheExactBlankLineSpan() = runBlocking {
        val source = "a".repeat(4_000) + "\n".repeat(102) + "b".repeat(4_000)

        assertFailsWith<IllegalArgumentException> {
            engine.inspect(
                contentText = source,
                sourceLabel = "large blank span",
                planner = FixedPlanner {
                    MemoryIngestPlan(
                        decision = MemoryIngestPlan.Decision.READY,
                        sections = listOf(MemoryIngestSectionPlan("Combined", listOf("b1", "b2"))),
                        reason = "Invalid oversized span.",
                    )
                },
            )
        }
        Unit
    }
}

private class FixedPlanner(
    private val block: suspend (MemoryIngestPlanningRequest) -> MemoryIngestPlan,
) : MemoryIngestPlanner {
    override suspend fun plan(request: MemoryIngestPlanningRequest): MemoryIngestPlan = block(request)
}
