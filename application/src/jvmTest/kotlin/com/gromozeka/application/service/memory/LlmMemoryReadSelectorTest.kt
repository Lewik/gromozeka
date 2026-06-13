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
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectorTrace
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LlmMemoryReadSelectorTest {
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
                    answerMode = MemoryReadPlan.AnswerMode.MIXED,
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
                    answerMode = MemoryReadPlan.AnswerMode.MIXED,
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
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
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
        assertTrue(runtime.finalCandidateIds.single().containsAll(listOf("source-01", "source-02", "source-03")))
        assertTrue(result.summary.contains("Final safety added"))
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
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
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
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
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
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1),
                ),
                candidateHits = listOf(MemoryStore.SearchHit.ClaimHit(claim, score = 1.0)),
                snapshot = MemoryNamespaceSnapshot(claims = listOf(claim)),
            )
        )

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("qualifiers_json"), prompt)
        assertTrue(prompt.contains("Rachel and Mike"), prompt)
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
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
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

    private class SelectingRuntime(
        private val intermediateSelectedIds: Set<String> = emptySet(),
        private val finalSelectedIds: Set<String> = intermediateSelectedIds,
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
            val rejected = candidateRefs.filterNot { it.id in selectedIds }

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
        ): MemoryClaim =
            MemoryClaim(
                id = MemoryClaim.Id(id),
                namespace = NAMESPACE,
                subjectEntityId = MemoryEntity.Id("entity-user"),
                predicate = predicate,
                predicateFamily = predicate,
                normalizedText = normalizedText,
                scope = MemoryScope.Global("test"),
                qualifiers = qualifiers,
                confidence = 0.9,
                importance = 6,
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                createdAt = NOW,
                updatedAt = NOW,
            )
    }
}
