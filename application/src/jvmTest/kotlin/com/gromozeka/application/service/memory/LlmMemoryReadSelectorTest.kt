package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionRequest
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

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
    }

    private class SelectingRuntime(
        private val intermediateSelectedIds: Set<String>,
        private val finalSelectedIds: Set<String> = intermediateSelectedIds,
    ) : AiRuntime {
        val candidateCounts = mutableListOf<Int>()
        val finalCandidateIds = mutableListOf<List<String>>()
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
            val candidateIds = Regex(""""type":"note","id":"([^"]+)"""")
                .findAll(prompt)
                .map { it.groupValues[1] }
                .toList()
            candidateCounts += candidateIds.size
            val isIntermediate = prompt.contains("Pass mode: intermediate_recall")
            if (!isIntermediate) {
                finalCandidateIds += candidateIds
            }
            val selectedIds = if (isIntermediate) intermediateSelectedIds else finalSelectedIds
            val selected = candidateIds.filter { it in selectedIds }
            val rejected = candidateIds.filterNot { it in selectedIds }

            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                Conversation.Message.StructuredText(
                                    """
                                    {
                                      "selected_items": [${selected.mapIndexed { index, id -> """{"item_type":"note","item_id":"$id","rank":${index + 1},"relevance":"direct_answer","reason":"selected"}""" }.joinToString(",")}],
                                      "rejected_items": [${rejected.joinToString(",") { id -> """{"item_type":"note","item_id":"$id","reason":"not selected"}""" }}],
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
    }
}
