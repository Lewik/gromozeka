package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectorTrace
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.AiRuntime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LlmMemoryReadSelectorTest {
    @Test
    fun selectsModerateCandidateSetInOneGlobalPass() = runBlocking {
        val notes = (1..33).map { index ->
            note(
                id = "direct-note-${index.toString().padStart(2, '0')}",
                title = "Direct candidate $index",
                summary = "Direct candidate $index exercises bounded global selection.",
            )
        }
        val runtime = SelectingRuntime(setOf("direct-note-07"))

        val result = LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("Which direct candidate matters?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.MIXED,
                    retrievalBudget = MemoryRetrievalBudget(notes = 1),
                ),
                candidateHits = notes.mapIndexed { index, note ->
                    MemoryStore.SearchHit.NoteHit(note, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(notes = notes),
            )
        )

        assertEquals(listOf(33), runtime.candidateCounts)
        assertEquals(listOf("direct-note-07"), result.selectedHits.map { (it as MemoryStore.SearchHit.NoteHit).note.id.value })
        assertEquals(listOf(MemoryReadSelectorTrace.Mode.FINAL_SELECTION), result.selectorTrace.stages.map { it.mode })
    }

    @Test
    fun batchesLargeCandidateSets() = runBlocking {
        val notes = (1..45).map { index ->
            note(
                id = "note-${index.toString().padStart(2, '0')}",
                title = "Candidate note $index",
                summary = "Candidate note $index explains memory selector batching.",
            )
        }
        val runtime = SelectingRuntime(setOf("note-07", "note-33"))

        val result = LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("What matters for memory selector batching?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.MIXED,
                    retrievalBudget = MemoryRetrievalBudget(notes = 2),
                ),
                candidateHits = notes.mapIndexed { index, note ->
                    MemoryStore.SearchHit.NoteHit(note, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(notes = notes),
            )
        )

        assertEquals(listOf(20, 20, 5, 8), runtime.candidateCounts)
        assertEquals(
            listOf("note-07", "note-33"),
            result.selectedHits.map { (it as MemoryStore.SearchHit.NoteHit).note.id.value },
        )
        assertTrue(result.summary.contains("Hierarchical selector"))
        assertEquals(45, result.selectorTrace.initialCandidateCount)
        assertEquals(8, result.selectorTrace.finalCandidateCount)
        assertEquals(2, result.selectorTrace.selectedCount)
        assertEquals(
            listOf(
                MemoryReadSelectorTrace.Mode.INTERMEDIATE_RECALL,
                MemoryReadSelectorTrace.Mode.INTERMEDIATE_RECALL,
                MemoryReadSelectorTrace.Mode.INTERMEDIATE_RECALL,
                MemoryReadSelectorTrace.Mode.FINAL_SELECTION,
            ),
            result.selectorTrace.stages.map { it.mode },
        )
        assertEquals(listOf(20, 20, 5, 8), result.selectorTrace.stages.map { it.inputCount })
        assertEquals(listOf(3, 3, 2, 2), result.selectorTrace.stages.map { it.outputCount })
    }

    @Test
    fun runsIntermediateBatchesWithConfiguredParallelism() = runBlocking {
        val notes = (1..45).map { index ->
            note(
                id = "parallel-note-${index.toString().padStart(2, '0')}",
                title = "Parallel candidate $index",
                summary = "Parallel candidate $index exercises bounded selector concurrency.",
            )
        }
        val runtime = ConcurrencyTrackingRuntime()

        val result = LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
            batchParallelism = 3,
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("Which parallel candidates matter?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.MIXED,
                    retrievalBudget = MemoryRetrievalBudget(notes = 2),
                ),
                candidateHits = notes.mapIndexed { index, note ->
                    MemoryStore.SearchHit.NoteHit(note, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(notes = notes),
            )
        )

        assertEquals(3, runtime.maxConcurrentCalls.get())
        assertEquals(4, runtime.totalCalls.get())
        assertEquals(4, runtime.conversationKeys.size)
        assertEquals(1, runtime.promptCacheKeys.size)
        assertEquals(4, result.selectorTrace.stages.size)
        assertEquals(listOf(20, 20, 5), result.selectorTrace.stages.dropLast(1).map { it.inputCount })
        assertEquals(MemoryReadSelectorTrace.Mode.FINAL_SELECTION, result.selectorTrace.stages.last().mode)
    }

    @Test
    fun keepsDeterministicSafetySurvivorsForFinalSelection() = runBlocking {
        val notes = (1..45).map { index ->
            note(
                id = "note-${index.toString().padStart(2, '0')}",
                title = "Candidate note $index",
                summary = "Candidate note $index explains memory selector batching.",
            )
        }
        val runtime = SelectingRuntime(
            intermediateSelectedIds = setOf("note-33"),
            finalSelectedIds = setOf("note-01"),
        )

        val result = LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("What matters for memory selector batching?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.MIXED,
                    retrievalBudget = MemoryRetrievalBudget(notes = 1),
                ),
                candidateHits = notes.mapIndexed { index, note ->
                    MemoryStore.SearchHit.NoteHit(note, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(notes = notes),
            )
        )

        assertEquals(listOf("note-01"), result.selectedHits.map { (it as MemoryStore.SearchHit.NoteHit).note.id.value })
        assertTrue(runtime.finalCandidateIds.single().contains("note-01"))
        assertTrue(
            result.selectorTrace.stages
                .filter { it.mode == MemoryReadSelectorTrace.Mode.INTERMEDIATE_RECALL }
                .any { stage -> stage.safetyAddedRefs.any { it.id == "note-01" } }
        )
    }

    @Test
    fun keepsTargetDateTemporalClaimsForFinalSelection() = runBlocking {
        val notes = (1..18).map { index ->
            note(
                id = "note-${index.toString().padStart(2, '0')}",
                title = "Distractor note $index",
                summary = "Distractor note $index has lexical overlap with music events.",
            )
        }
        val wrongDate = claim(
            id = "claim-wrong-date",
            normalizedText = "The user attended a music festival with friends on 2023-04-01.",
            predicate = "attended_event",
            validFrom = Instant.parse("2023-04-01T10:00:00Z"),
        )
        val otherDate = claim(
            id = "claim-other-date",
            normalizedText = "The user attended a jazz night with coworkers on 2023-04-08.",
            predicate = "attended_event",
            validFrom = Instant.parse("2023-04-08T10:00:00Z"),
        )
        val targetDate = claim(
            id = "claim-target-date",
            normalizedText = "The user attended a rock concert with parents on 2023-04-15.",
            predicate = "attended_event",
            validFrom = Instant.parse("2023-04-15T10:00:00Z"),
        )
        val laterNotes = (19..23).map { index ->
            note(
                id = "note-${index.toString().padStart(2, '0')}",
                title = "Later distractor note $index",
                summary = "Later distractor note $index keeps the selector hierarchical.",
            )
        }
        val runtime = SelectingRuntime(
            intermediateSelectedIds = setOf("note-01", "claim-wrong-date"),
            finalSelectedIds = setOf("claim-wrong-date"),
        )

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest(
                    """
                    LongMemEval recall target.
                    Current date: 2023/04/22 (Sat) 11:24
                    Question: Who did I go with to the music event last Saturday?
                    """.trimIndent()
                ),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 3, notes = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            query = "music event last Saturday 2023-04-15 went with accompanied by who",
                            topK = 3,
                        )
                    ),
                ),
                candidateHits = (
                    notes.mapIndexed { index, note -> MemoryStore.SearchHit.NoteHit(note, score = 1.0 - index / 100.0) } +
                        listOf(
                            MemoryStore.SearchHit.ClaimHit(wrongDate, score = 0.95),
                            MemoryStore.SearchHit.ClaimHit(otherDate, score = 0.94),
                            MemoryStore.SearchHit.ClaimHit(targetDate, score = 0.01),
                        ) +
                        laterNotes.mapIndexed { index, note -> MemoryStore.SearchHit.NoteHit(note, score = 0.5 - index / 100.0) }
                    ),
                snapshot = MemoryNamespaceSnapshot(
                    claims = listOf(wrongDate, otherDate, targetDate),
                    notes = notes + laterNotes,
                ),
            )
        )

        assertTrue(
            runtime.finalCandidateIds.single().contains("claim-target-date"),
            "Temporal safety must carry the target-date claim into final selection.",
        )
    }

    @Test
    fun keepsMultipleEvidenceSurvivorsForCompleteSet() = runBlocking {
        val sources = (1..6).map { index ->
            source(
                id = "source-${index.toString().padStart(2, '0')}",
                text = "Source $index may contain a separate counted item.",
            )
        }
        val notes = (1..39).map { index ->
            note(
                id = "note-${index.toString().padStart(2, '0')}",
                title = "Distractor note $index",
                summary = "Distractor note $index is more strongly scored than source evidence.",
            )
        }
        val runtime = SelectingRuntime(
            intermediateSelectedIds = setOf("note-33"),
            finalSelectedIds = setOf("source-01"),
        )
        val hits = notes.take(14).mapIndexed { index, note ->
            MemoryStore.SearchHit.NoteHit(note, score = 1.0 - index / 100.0)
        } + sources.mapIndexed { index, source ->
            MemoryStore.SearchHit.SourceHit(source, score = 0.2 - index / 100.0)
        } + notes.drop(14).mapIndexed { index, note ->
            MemoryStore.SearchHit.NoteHit(note, score = 0.8 - index / 100.0)
        }

        val result = LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("How many distinct matching items are remembered?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(notes = 1, sources = 3),
                    requireEvidenceFallback = true,
                ),
                candidateHits = hits,
                snapshot = MemoryNamespaceSnapshot(notes = notes, sources = sources),
            )
        )

        assertEquals(
            listOf("source-01", "source-02", "source-03"),
            result.selectedHits.filterIsInstance<MemoryStore.SearchHit.SourceHit>().map { it.source.id.value },
        )
        assertTrue(runtime.finalCandidateIds.flatten().containsAll(listOf("source-01", "source-02", "source-03")))
        assertTrue(result.summary.contains("Deterministic safety added"))
    }

    @Test
    fun keepsTypedEvidenceFromSelectedSourceForCompleteSet() = runBlocking {
        val source = source(
            id = "source-personal-copy",
            text = "The source contains a low lexical score personal item that still belongs to the complete set.",
        )
        val evidenceClaim = claim(
            id = "claim-personal-copy",
            normalizedText = "The user owns a signed personal music-media copy.",
            predicate = "owns",
            evidenceSourceId = source.id.value,
        )
        val notes = (1..43).map { index ->
            note(
                id = "note-${index.toString().padStart(2, '0')}",
                title = "Distractor note $index",
                summary = "Distractor note $index has stronger lexical score than the evidence-backed claim.",
            )
        }
        val hits = notes.take(38).mapIndexed { index, note ->
            MemoryStore.SearchHit.NoteHit(note, score = 1.0 - index / 100.0)
        } + listOf(
            MemoryStore.SearchHit.SourceHit(source, score = 0.61),
            MemoryStore.SearchHit.ClaimHit(evidenceClaim, score = 0.05),
        ) + notes.drop(38).mapIndexed { index, note ->
            MemoryStore.SearchHit.NoteHit(note, score = 0.5 - index / 100.0)
        }
        val runtime = SelectingRuntime(
            intermediateSelectedIds = setOf("note-38"),
            finalSelectedIds = setOf("source-personal-copy"),
        )

        val result = LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("How many complete-set items did I acquire?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(notes = 1, sources = 1, claims = 1),
                    requireEvidenceFallback = true,
                ),
                candidateHits = hits,
                snapshot = MemoryNamespaceSnapshot(
                    claims = listOf(evidenceClaim),
                    notes = notes,
                    sources = listOf(source),
                ),
            )
        )

        assertTrue(runtime.finalCandidateIds.flatten().contains("claim-personal-copy"))
        assertTrue(
            result.selectedHits
                .filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
                .any { it.claim.id.value == "claim-personal-copy" }
        )
    }

    @Test
    fun usesSingleFinalPassForModerateCompleteSetCandidateSets() = runBlocking {
        val sources = (1..6).map { index ->
            source(
                id = "source-${index.toString().padStart(2, '0')}",
                text = "Source $index may contain a separate counted item.",
            )
        }
        val notes = (1..27).map { index ->
            note(
                id = "note-${index.toString().padStart(2, '0')}",
                title = "Candidate note $index",
                summary = "Candidate note $index is part of a moderate complete-set candidate pool.",
            )
        }
        val runtime = SelectingRuntime(
            intermediateSelectedIds = emptySet(),
            finalSelectedIds = setOf("source-06"),
        )
        val hits = notes.mapIndexed { index, note ->
            MemoryStore.SearchHit.NoteHit(note, score = 1.0 - index / 100.0)
        } + sources.mapIndexed { index, source ->
            MemoryStore.SearchHit.SourceHit(source, score = 0.3 - index / 100.0)
        }

        val result = LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("How many distinct matching items are remembered?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(notes = 1, sources = 3),
                    requireEvidenceFallback = true,
                ),
                candidateHits = hits,
                snapshot = MemoryNamespaceSnapshot(notes = notes, sources = sources),
            )
        )

        assertEquals(listOf(33), runtime.candidateCounts)
        assertEquals(listOf(MemoryReadSelectorTrace.Mode.FINAL_SELECTION), result.selectorTrace.stages.map { it.mode })
        assertTrue(runtime.finalCandidateIds.single().containsAll(sources.map { it.id.value }))
        assertTrue(
            result.selectedHits
                .filterIsInstance<MemoryStore.SearchHit.SourceHit>()
                .any { it.source.id.value == "source-06" }
        )
    }

    @Test
    fun completeSetEvaluatesEachCandidateOnceWhenModelSelectsEverything() = runBlocking {
        val notes = (1..85).map { index ->
            note(
                id = "complete-note-${index.toString().padStart(2, '0')}",
                title = "Complete candidate $index",
                summary = "Complete candidate $index belongs to the requested set.",
            )
        }
        val selectedIds = notes.mapTo(mutableSetOf()) { it.id.value }
        val runtime = SelectingRuntime(finalSelectedIds = selectedIds)

        val result = LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
            batchParallelism = 3,
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("List every complete candidate."),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(notes = notes.size),
                ),
                candidateHits = notes.mapIndexed { index, note ->
                    MemoryStore.SearchHit.NoteHit(note, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(notes = notes),
            )
        )

        assertEquals(listOf(5, 40, 40), runtime.candidateCounts.sorted())
        assertEquals(notes.size, result.selectedHits.size)
        assertEquals(notes.size, result.decisions.count { it.selected })
        assertEquals(
            List(3) { MemoryReadSelectorTrace.Mode.COMPLETE_SET_SELECTION },
            result.selectorTrace.stages.map { it.mode },
        )
        assertEquals(notes.size, result.selectorTrace.initialCandidateCount)
        assertEquals(notes.size, result.selectorTrace.finalCandidateCount)
        assertEquals(notes.size, result.selectorTrace.selectedCount)
    }

    @Test
    fun promptRequiresTemporalAnchorFactsForRelativeTimeArithmetic() = runBlocking {
        val claims = listOf(
            claim(
                id = "claim-anchor",
                normalizedText = "The user attended their best friend's wedding in San Francisco around 2023-03-27.",
                predicate = "attended_event",
            ),
            claim(
                id = "claim-offset",
                normalizedText = "The user stayed in Haight-Ashbury after booking the Airbnb three months in advance.",
                predicate = "attended_event",
            ),
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf("claim-offset"))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("How many months ago did I book the Airbnb in San Francisco?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2),
                ),
                candidateHits = claims.mapIndexed { index, claim ->
                    MemoryStore.SearchHit.ClaimHit(claim, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(claims = claims),
            )
        )

        assertTrue(
            runtime.prompts.single().contains(
                "Rejecting an event-date or question-date anchor as \"not needed\" is incorrect"
            )
        )
        assertTrue(
            runtime.prompts.single().contains(
                "that candidate supplies an offset, not elapsed time from the current/question date"
            )
        )
    }

    @Test
    fun promptComparesEventDateBeforeSourceDateForNamedDateQuestions() = runBlocking {
        val claims = listOf(
            claim(
                id = "claim-explicit-wrong-date",
                normalizedText = "The user flew American Airlines on 2023-02-10.",
                predicate = "attended_event",
            ),
            claim(
                id = "claim-source-date-target",
                normalizedText = "Session date: 2023/02/14. The user was still recovering from an American Airlines flight from LAX to JFK.",
                predicate = "attended_event",
            ),
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf("claim-explicit-wrong-date"))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest(
                    """
                    LongMemEval recall target.
                    Current date: 2023/03/02 (Thu) 21:56
                    Question: What was the airline that I flied with on Valentine's day?
                    """.trimIndent()
                ),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2),
                ),
                candidateHits = claims.mapIndexed { index, claim ->
                    MemoryStore.SearchHit.ClaimHit(claim, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(claims = claims),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("For named-date or relative-date questions"), prompt)
        assertTrue(prompt.contains("candidate event dates and local event wording are stronger"), prompt)
        assertTrue(prompt.contains("Keep a source/session-date match only when"), prompt)
        assertTrue(prompt.contains("For completed-experience questions"), prompt)
        assertTrue(prompt.contains("A booking or reservation date is not the event date"), prompt)
        assertTrue(prompt.contains("relative month-count questions such as \"two months ago\""), prompt)
        assertTrue(prompt.contains("one month ago is not two months ago"), prompt)
        assertTrue(prompt.contains("derive the target interval from the current/question date"), prompt)
        assertTrue(prompt.contains("First-person recency wording such as \"still recovering from\""), prompt)
        assertTrue(prompt.contains("Do not smear one relative date cue"), prompt)
        assertTrue(prompt.contains("Missing date evidence is uncertainty"), prompt)
        assertTrue(prompt.contains("target-period lifecycle variants"), prompt)
        assertTrue(prompt.contains("requested action/status is not itself a required qualifier"), prompt)
        assertTrue(prompt.contains("place-visit questions"), prompt)
        assertTrue(prompt.contains("user-attended venue events such as lectures"), prompt)
        assertTrue(prompt.contains("Do not infer that an unrelated explicit date satisfies a named date"), prompt)
    }

    @Test
    fun promptRequiresAllOperandsForDerivedDurationAnswers() = runBlocking {
        val claims = listOf(
            claim(
                id = "claim-company-tenure",
                normalizedText = "The user had 3 years and 9 months of experience at their company as of 2023-05-22.",
                predicate = "current_metric_value",
            ),
            claim(
                id = "claim-promotion-offset",
                normalizedText = "The user started as a Marketing Coordinator and worked up to Senior Marketing Specialist after 2 years and 4 months.",
                predicate = "current_metric_value",
            ),
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf("claim-company-tenure"))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest(
                    """
                    LongMemEval recall target.
                    Current date: 2023/05/30 (Tue) 03:15
                    Question: How long have I been working in my current role?
                    """.trimIndent()
                ),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2),
                ),
                candidateHits = claims.mapIndexed { index, claim ->
                    MemoryStore.SearchHit.ClaimHit(claim, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(claims = claims),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(
            prompt.contains("For derived duration answers, keep every operand needed for the calculation"),
            prompt,
        )
        assertTrue(
            prompt.contains("current role tenure may require both total company tenure and time before promotion"),
            prompt,
        )
    }

    @Test
    fun promptKeepsIntermediateFormalEducationForStageToDegreeDuration() = runBlocking {
        val claims = listOf(
            claim(
                id = "claim-high-school",
                normalizedText = "The user attended high school from 2010 to 2014.",
                predicate = "attended_education",
            ),
            claim(
                id = "claim-associate",
                normalizedText = "The user completed an associate degree at community college in 2016.",
                predicate = "completed_education",
            ),
            claim(
                id = "claim-bachelor",
                normalizedText = "The user completed a bachelor's degree in 2020.",
                predicate = "completed_education",
            ),
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf("claim-high-school", "claim-bachelor"))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("How many years did I spend in formal education from high school to my Bachelor's degree?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 3),
                ),
                candidateHits = claims.mapIndexed { index, claim ->
                    MemoryStore.SearchHit.ClaimHit(claim, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(claims = claims),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(
            prompt.contains("For formal education timeline or duration questions"),
            prompt,
        )
        assertTrue(
            prompt.contains("keep intermediate formal education credentials"),
            prompt,
        )
    }

    @Test
    fun promptRejectsSingularPossessionsWhenAggregateMetricOperandsDefineCountedInventory() = runBlocking {
        val claims = listOf(
            claim(
                id = "claim-rare-books",
                normalizedText = "The user's rare book collection contained 5 books.",
                predicate = "current_metric_value",
            ),
            claim(
                id = "claim-antique-vase",
                normalizedText = "The user owns their grandmother's antique vase.",
                predicate = "owns",
            ),
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf("claim-rare-books"))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("How many rare items do I have in total?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2),
                ),
                candidateHits = claims.mapIndexed { index, claim ->
                    MemoryStore.SearchHit.ClaimHit(claim, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(claims = claims),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("AGGREGATE_VALUE and AGGREGATE_DELTA claims are numeric operands"), prompt)
        assertTrue(prompt.contains("POSSESSION and COUNTABLE_ITEM claims are direct evidence"), prompt)
        assertTrue(prompt.contains("Do not decide by lexical overlap alone"), prompt)
        assertTrue(prompt.contains("Preserve the uncertainty for the answerer"), prompt)
    }

    @Test
    fun promptKeepsContentCarriersForBroadWorkCounts() = runBlocking {
        val claims = listOf(
            claim(
                id = "claim-personal-media-copy",
                normalizedText = "The user owns a signed personal media copy after the show.",
                predicate = "owns",
            )
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf("claim-personal-media-copy"))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("How many creative works or content items have I acquired?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1),
                ),
                candidateHits = claims.mapIndexed { index, claim ->
                    MemoryStore.SearchHit.ClaimHit(claim, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(claims = claims),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("category fit is semantic, not exact-word matching"), prompt)
        assertTrue(prompt.contains("concrete user-attributed member, copy, carrier, component, or subtype"), prompt)
    }

    @Test
    fun promptDoesNotPromotePlainProjectAssociationAsLeadershipEvidence() = runBlocking {
        val claims = listOf(
            claim(
                id = "claim-project-association",
                normalizedText = "The user was working on research about a broad topic.",
                predicate = "works_on_project",
            )
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf("claim-project-association"))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("How many projects have I led or am currently leading?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1),
                ),
                candidateHits = claims.mapIndexed { index, claim ->
                    MemoryStore.SearchHit.ClaimHit(claim, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(claims = claims),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("RESPONSIBILITY and FUNCTIONAL_ROLE claims are direct evidence"), prompt)
        assertTrue(prompt.contains("PROJECT_ASSOCIATION alone is supporting context"), prompt)
    }

    @Test
    fun promptKeepsLaterPlansForCurrentStateQuestionsAtLaterTargetDate() = runBlocking {
        val claims = listOf(
            claim(
                id = "claim-current-location",
                normalizedText = "The user's old sneakers are stored under the user's bed.",
                predicate = "current_location",
            ),
            claim(
                id = "claim-later-storage-plan",
                normalizedText = "The user plans to store the old sneakers in a shoe rack after organizing the closet this weekend.",
                predicate = "has_goal",
            ),
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf("claim-current-location"))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest(
                    """
                    LongMemEval recall target.
                    Current date: 2023/06/07 (Wed) 04:15
                    Question: Where do I currently keep my old sneakers?
                    """.trimIndent()
                ),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2),
                ),
                candidateHits = claims.mapIndexed { index, claim ->
                    MemoryStore.SearchHit.ClaimHit(claim, score = 1.0 - index / 100.0)
                },
                snapshot = MemoryNamespaceSnapshot(claims = claims),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(
            prompt.contains("keep later dated plans, intentions, or scheduled changes"),
            prompt,
        )
        assertTrue(
            prompt.contains("Select them together with any older current-state candidate"),
            prompt,
        )
    }

    @Test
    fun rendersClaimQualifiersForSelector() = runBlocking {
        val claim = claim(
            id = "claim-rachel",
            normalizedText = "The user attended Rachel and Mike's wedding at a vineyard in August 2023.",
            predicate = "attended_event",
            qualifiers = JsonObject(
                mapOf(
                    "participants" to JsonPrimitive("Rachel and Mike"),
                )
            ),
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf("claim-rachel"))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("How many weddings did I attend this year?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1),
                ),
                candidateHits = listOf(MemoryStore.SearchHit.ClaimHit(claim, score = 1.0)),
                snapshot = MemoryNamespaceSnapshot(claims = listOf(claim)),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("qualifiers_json"), prompt)
        assertTrue(prompt.contains("Rachel and Mike"), prompt)
        assertTrue(prompt.contains("A shared anchor can connect candidates only when it explicitly identifies the same fully-qualified target"), prompt)
        assertTrue(prompt.contains("Keep anchor-level metadata candidates when they can supply a missing requested detail"), prompt)
        assertTrue(prompt.contains("Reject the bridge when retrieved memory has competing anchors or competing values for the same target"), prompt)
    }

    @Test
    fun omitsVolatileSourceIngestionTimestampsFromSelectorPrompt() = runBlocking {
        val source = source(
            id = "source-dated-at-ingest",
            text = "Past chat session. Session date: 2023/08/30. User bought a 70-200mm zoom lens.",
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf(source.id.value))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("What camera lens did I purchase most recently?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(sources = 1),
                ),
                candidateHits = listOf(MemoryStore.SearchHit.SourceHit(source, score = 1.0)),
                snapshot = MemoryNamespaceSnapshot(sources = listOf(source)),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("Session date: 2023/08/30"), prompt)
        assertTrue(!prompt.contains("observed_at"), prompt)
        assertTrue(!prompt.contains("created_at"), prompt)
        assertTrue(!prompt.contains(NOW.toString()), prompt)
    }

    @Test
    fun sourceCandidateViewUsesCompactSelectorPayload() {
        val source = source(
            id = "source-compact-selector",
            text = "c".repeat(1_400) + "CONTENT_TAIL",
        ).copy(
            searchText = "s".repeat(800) + "SEARCH_TAIL",
        )

        val rendered = MemoryReadSelectorCandidateRenderer.render(
            hits = listOf(MemoryStore.SearchHit.SourceHit(source, score = 1.0)),
            snapshot = MemoryNamespaceSnapshot(sources = listOf(source)),
            query = "",
        )

        assertTrue(rendered.contains("[truncated"), rendered)
        assertTrue(!rendered.contains("SEARCH_TAIL"), rendered)
        assertTrue(!rendered.contains("CONTENT_TAIL"), rendered)
    }

    @Test
    fun selectorPromptKeepsBaselineOperandsForMetricDeltaQuestions() = runBlocking {
        val baseline = claim(
            id = "claim-baseline",
            normalizedText = "The user's newsletter had 250 subscribers at the initial baseline.",
            predicate = "current_metric_value",
        )
        val later = claim(
            id = "claim-later",
            normalizedText = "The user's newsletter had 350 subscribers after two weeks.",
            predicate = "metric_observation",
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf(baseline.id.value, later.id.value))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("What was the increase in newsletter subscribers after two weeks?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2),
                ),
                candidateHits = listOf(
                    MemoryStore.SearchHit.ClaimHit(later, score = 1.0),
                    MemoryStore.SearchHit.ClaimHit(baseline, score = 0.8),
                ),
                snapshot = MemoryNamespaceSnapshot(claims = listOf(baseline, later)),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("AGGREGATE_VALUE and AGGREGATE_DELTA claims are numeric operands"), prompt)
        assertTrue(prompt.contains("Rejecting a baseline, anchor date, boundary event, or source date can make the answer impossible"), prompt)
    }

    @Test
    fun selectorPromptKeepsAnchorEventForRelativeDurationQuestions() = runBlocking {
        val earlierEvent = claim(
            id = "claim-workshop",
            normalizedText = "The user attended the workshop on 2024-02-01.",
            predicate = "attended_event",
        )
        val anchorEvent = claim(
            id = "claim-launch",
            normalizedText = "The product launch happened on 2024-02-10.",
            predicate = "attended_event",
        )
        val runtime = SelectingRuntime(finalSelectedIds = setOf(earlierEvent.id.value, anchorEvent.id.value))

        LlmMemoryReadSelector(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).select(
            MemoryReadSelectionRequest(
                readRequest = readRequest("How many days ago did I attend the workshop when the product launch happened?"),
                plan = MemoryReadPlan(
                    needMemory = true,
                    contextMode = MemoryReadPlan.ContextMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2),
                ),
                candidateHits = listOf(
                    MemoryStore.SearchHit.ClaimHit(earlierEvent, score = 1.0),
                    MemoryStore.SearchHit.ClaimHit(anchorEvent, score = 0.8),
                ),
                snapshot = MemoryNamespaceSnapshot(claims = listOf(earlierEvent, anchorEvent)),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("relative-duration questions that name an anchor event"), prompt)
        assertTrue(prompt.contains("Do not default to the current/question date"), prompt)
        assertTrue(prompt.contains("keep explicit start/begin/first-participation evidence"), prompt)
        assertTrue(prompt.contains("Do not reject the start event as less direct"), prompt)
    }

    private class SelectingRuntime(
        private val intermediateSelectedIds: Set<String> = emptySet(),
        private val finalSelectedIds: Set<String> = intermediateSelectedIds,
        private val emitRejectedItems: Boolean = true,
    ) : AiRuntime {
        val candidateCounts = mutableListOf<Int>()
        val finalCandidateIds = mutableListOf<List<String>>()
        val prompts = mutableListOf<String>()
        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            val prompt = request.messages.joinToString("\n") { message ->
                message.content.joinToString("\n") { item ->
                    when (item) {
                        is Conversation.Message.ContentItem.UserMessage -> item.text
                        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                        else -> item.toString()
                    }
                }
            }
            prompts += prompt
            val candidateRefs = Regex(""""type":"([^"]+)","id":"([^"]+)"""")
                .findAll(prompt)
                .map { CandidateRef(type = it.groupValues[1], id = it.groupValues[2]) }
                .toList()
            candidateCounts += candidateRefs.size
            val isIntermediate = prompt.contains("Pass mode: intermediate_recall")
            if (!isIntermediate) {
                finalCandidateIds += candidateRefs.map { it.id }
            }
            val selectedIds = if (isIntermediate) intermediateSelectedIds else finalSelectedIds
            val selected = candidateRefs.filter { it.id in selectedIds }
            val rejected = if (emitRejectedItems) {
                candidateRefs.filterNot { it.id in selectedIds }
            } else {
                emptyList()
            }

            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                Conversation.Message.StructuredText(
                                    """
                                    {
                                      "selected_items": [${selected.mapIndexed { index, ref -> """{"item_type":"${ref.type}","item_id":"${ref.id}","rank":${index + 1},"relevance":"direct_answer","reason":"selected"}""" }.joinToString(",")}],
                                      "rejected_items": [${rejected.joinToString(",") { ref -> """{"item_type":"${ref.type}","item_id":"${ref.id}","reason":"not selected"}""" }}],
                                      "summary": "selected ${selected.size}"
                                    }
                                    """.trimIndent()
                                )
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()

        private data class CandidateRef(
            val type: String,
            val id: String,
        )
    }

    private class ConcurrencyTrackingRuntime : AiRuntime {
        val maxConcurrentCalls = AtomicInteger()
        val totalCalls = AtomicInteger()
        val conversationKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val promptCacheKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val activeCalls = AtomicInteger()
        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            totalCalls.incrementAndGet()
            (request.options.toolContext["conversationId"] as? String)?.let(conversationKeys::add)
            (request.options.toolContext["promptCacheKey"] as? String)?.let(promptCacheKeys::add)
            val active = activeCalls.incrementAndGet()
            maxConcurrentCalls.updateAndGet { current -> maxOf(current, active) }
            try {
                delay(50)
            } finally {
                activeCalls.decrementAndGet()
            }

            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                Conversation.Message.StructuredText(
                                    """
                                    {
                                      "selected_items": [],
                                      "rejected_items": [],
                                      "summary": "No explicit selections."
                                    }
                                    """.trimIndent()
                                )
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }

    private companion object {
        private val NAMESPACE = MemoryNamespace("read-selector-test")
        private val NOW = Instant.parse("2026-01-02T03:04:05Z")

        private fun readRequest(text: String): MemoryReadRequest {
            val message = Conversation.Message(
                id = Conversation.Message.Id("target-message"),
                conversationId = Conversation.Id("conversation"),
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
                createdAt = NOW,
            )
            return MemoryReadRequest(
                namespace = NAMESPACE,
                threadContext = com.gromozeka.domain.model.memory.MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("thread"),
                    targetMessageId = message.id,
                    messages = listOf(message),
                ),
            )
        }

        private fun note(
            id: String,
            title: String,
            summary: String,
        ): MemoryNote =
            MemoryNote(
                id = MemoryNote.Id(id),
                namespace = NAMESPACE,
                noteType = MemoryNote.Type.CONTEXT,
                title = title,
                summary = summary,
                scope = MemoryScope.Global("test"),
                status = MemoryNote.Status.ACTIVE,
                maturity = MemoryNote.Maturity.STABILIZING,
                confidence = 0.8,
                importance = 5,
                createdAt = NOW,
                updatedAt = NOW,
            )

        private fun source(
            id: String,
            text: String,
        ): MemorySource.ExternalRecord =
            MemorySource.ExternalRecord(
                id = MemorySource.Id(id),
                namespace = NAMESPACE,
                recordRef = "test:$id",
                contentText = text,
                contentHash = id,
                observedAt = NOW,
                createdAt = NOW,
            )

        private fun claim(
            id: String,
            normalizedText: String,
            predicate: String,
            qualifiers: JsonObject = JsonObject(emptyMap()),
            evidenceSourceId: String? = null,
            validFrom: Instant? = null,
        ): MemoryClaim =
            MemoryClaim(
                id = MemoryClaim.Id(id),
                namespace = NAMESPACE,
                subjectEntityId = MemoryEntity.Id("entity-user"),
                predicate = predicate,
                predicateFamily = predicate,
                predicatePolicy = testPredicatePolicy(predicate),
                normalizedText = normalizedText,
                scope = MemoryScope.Global("test"),
                qualifiers = qualifiers,
                confidence = 0.9,
                importance = 6,
                validFrom = validFrom,
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                evidenceRefs = evidenceSourceId?.let {
                    listOf(
                        MemoryEvidenceRef(
                            sourceId = MemorySource.Id(it),
                            kind = MemoryEvidenceRef.Kind.DIRECT,
                            cachedQuote = "test quote",
                        )
                    )
                } ?: emptyList(),
                createdAt = NOW,
                updatedAt = NOW,
            )

        private fun testPredicatePolicy(predicate: String): MemoryPredicateDefinition =
            when (predicate) {
                "attended_event" -> MemoryPredicateDefinition(
                    predicate = predicate,
                    namespace = NAMESPACE,
                    temporalPolicy = MemoryPredicateDefinition.TemporalPolicy.TIME_SCOPED,
                    semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.EVENT_PARTICIPATION),
                )

                "current_metric_value" -> MemoryPredicateDefinition(
                    predicate = predicate,
                    namespace = NAMESPACE,
                    temporalPolicy = MemoryPredicateDefinition.TemporalPolicy.STATUS_LIKE,
                    semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.AGGREGATE_VALUE),
                )

                "metric_observation" -> MemoryPredicateDefinition(
                    predicate = predicate,
                    namespace = NAMESPACE,
                    temporalPolicy = MemoryPredicateDefinition.TemporalPolicy.TIME_SCOPED,
                    semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.AGGREGATE_VALUE),
                )

                "owns" -> MemoryPredicateDefinition(
                    predicate = predicate,
                    namespace = NAMESPACE,
                    temporalPolicy = MemoryPredicateDefinition.TemporalPolicy.STATUS_LIKE,
                    semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.POSSESSION),
                )

                else -> MemoryPredicateDefinition(
                    predicate = predicate,
                    namespace = NAMESPACE,
                    semanticKinds = setOf(MemoryPredicateDefinition.SemanticKind.OTHER),
                )
            }
    }
}
