package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionResult
import com.gromozeka.domain.model.memory.MemoryReadSelector
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LlmMemoryReadSelector(
    private val runtime: AiRuntime,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryReadSelector {
    private val log = KLoggers.logger(this)

    override suspend fun select(request: MemoryReadSelectionRequest): MemoryReadSelectionResult {
        if (request.candidateHits.isEmpty()) {
            return MemoryReadSelectionResult(selectedHits = emptyList(), summary = "No candidates.")
        }

        val renderedCandidates = MemoryReadSelectorCandidateRenderer.render(request.candidateHits, request.snapshot)
        val stageMessages = request.readRequest.toMemoryStageMessages(
            stageName = "read-selector-reranker",
            taskPrompt = buildSelectorPrompt(request, renderedCandidates),
        )

        log.info {
            "Memory read selector LLM call: namespace=${request.readRequest.namespace.value} " +
                "candidates=${request.candidateHits.size} answerMode=${request.plan.answerMode.name} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size} stageMessages=${stageMessages.size}"
        }
        log.info {
            "Memory read selector candidates rendered: namespace=${request.readRequest.namespace.value} " +
                "chars=${renderedCandidates.length} preview=${renderedCandidates.oneLineForReadSelectorLog(8_000)}"
        }

        val response = runtime.callMemoryStageWithRetry(
            AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxTokens = 2_400,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.ReadSelector,
                    toolContext = mapOf(
                        "memoryReadSelector" to true,
                        "memoryNamespace" to request.readRequest.namespace.value,
                        "conversationId" to "memory:${request.readRequest.threadContext.conversationId.value}",
                        "promptCacheKey" to request.readRequest.threadContext.conversationId.value,
                    ),
                ),
            ),
            stageName = "read-selector-reranker",
            logContext = "namespace=${request.readRequest.namespace.value}",
        )

        val rawText = response.messages
            .flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
            .trim()

        log.info {
            "Memory read selector raw response: namespace=${request.readRequest.namespace.value} chars=${rawText.length} " +
                "response=${rawText.oneLineForReadSelectorLog(4_000)}"
        }

        val jsonText = rawText.extractJsonObject()
            ?: throw IllegalStateException("Memory read selector did not return JSON: ${rawText.take(500)}")
        val selectorResponse = json.decodeFromString<ReadSelectorResponse>(jsonText)
        val hitsByRef = request.candidateHits.associateBy { it.toReadSelectorItemRef() }
        val selectedRefs = selectorResponse.selectedItems
            .sortedWith(compareBy<SelectedItem> { it.rank.coerceAtLeast(0) }.thenBy { it.itemId })
            .mapNotNull { it.toItemRef() }
            .distinct()
        val selectedHits = selectedRefs.mapNotNull(hitsByRef::get)
        val decisions = selectorResponse.toDecisions()

        log.info {
            "Memory read selector completed: namespace=${request.readRequest.namespace.value} " +
                "candidates=${request.candidateHits.size} selected=${selectedHits.size} rejected=${selectorResponse.rejectedItems.size} " +
                "selectedRefs=${selectedRefs.joinToString("|") { "${it.type.name.lowercase()}:${it.id}" }} " +
                "summary=${selectorResponse.summary.oneLineForReadSelectorLog(500)}"
        }

        return MemoryReadSelectionResult(
            selectedHits = selectedHits,
            decisions = decisions,
            summary = selectorResponse.summary,
        )
    }

    private fun buildSelectorPrompt(
        request: MemoryReadSelectionRequest,
        renderedCandidates: String,
    ): String = """
        Memory stage: ReadSelectorReranker v1.
        Namespace: ${request.readRequest.namespace.value}

        Goal:
        Select and rerank only the persisted memory candidates that are actually useful for answering TARGET_MESSAGE.

        Selection rules:
        - Select the smallest sufficient set.
        - Candidate memory items are JSON records. Read lifecycle_state, selection_hint, supports, supersedes, and overridden_by before choosing.
        - Reject candidates that only have vague lexical overlap.
        - Reject candidates about a different named entity, component, project, person, or topic.
        - Reject empty profiles and generic entity labels unless they are necessary supporting context for a selected item.
        - Prefer active claims for factual answers.
        - Prefer episodes for reusable lessons and "what did we learn" questions.
        - Prefer notes for rationale, decisions, plans, and contextual meaning.
        - Prefer tasks only for open commitments or workflow state.
        - Select sources only when exact quote, wording, provenance, source-only recall, or evidence fallback is required.
        - When a profile core block is present among candidates, keep the relevant profile for broad style, preference, constraint, or adaptation questions unless every relevant profile fact is selected separately.
        - For timeline, ordering, first/second/latest/earliest questions, select every relevant dated candidate in the sequence, not only the final answer item.
        - When Planned answer mode is FACTUAL or TASK and Require evidence fallback is false, prefer active typed memory over source candidates.
        - Raw sources are evidence, not current truth. Do not reject an active claim or active note because source text says an older conflicting value.
        - Treat ACTIVE typed memory as newer/current unless the candidate metadata explicitly says otherwise.
        - Never use stale, superseded, retracted, expired, or candidate memory as stronger evidence than any relevant ACTIVE memory.
        - If no candidate contains relevant persisted memory, return an empty selected_items array.

        Planned answer mode: ${request.plan.answerMode.name}
        Require evidence fallback: ${request.plan.requireEvidenceFallback}
        Retrieval budget: ${request.plan.retrievalBudget.renderForReadSelector()}
        Target message:
        ${request.readRequest.targetTextForReadSelector()}

        Candidate memory items:
        $renderedCandidates

        Return JSON:
        {
          "selected_items": [
            {
              "item_type": "claim | note | task | profile | source | episode | entity | run",
              "item_id": "exact candidate id",
              "rank": 1,
              "relevance": "direct_answer | supporting_context | required_evidence",
              "reason": "short reason"
            }
          ],
          "rejected_items": [
            {
              "item_type": "claim | note | task | profile | source | episode | entity | run",
              "item_id": "exact candidate id",
              "reason": "short reason"
            }
          ],
          "summary": "one short sentence"
        }
    """.trimIndent()

    @Serializable
    private data class ReadSelectorResponse(
        @SerialName("selected_items")
        val selectedItems: List<SelectedItem> = emptyList(),
        @SerialName("rejected_items")
        val rejectedItems: List<RejectedItem> = emptyList(),
        val summary: String = "",
    ) {
        fun toDecisions(): List<MemoryReadSelectionResult.Decision> =
            selectedItems.mapNotNull { item ->
                item.toItemRef()?.let { ref ->
                    MemoryReadSelectionResult.Decision(
                        ref = ref,
                        selected = true,
                        rank = item.rank,
                        reason = item.reason,
                    )
                }
            } + rejectedItems.mapNotNull { item ->
                item.toItemRef()?.let { ref ->
                    MemoryReadSelectionResult.Decision(
                        ref = ref,
                        selected = false,
                        rank = Int.MAX_VALUE,
                        reason = item.reason,
                    )
                }
            }
    }

    @Serializable
    private data class SelectedItem(
        @SerialName("item_type")
        val itemType: String,
        @SerialName("item_id")
        val itemId: String,
        val rank: Int = 0,
        val relevance: String = "",
        val reason: String = "",
    ) {
        fun toItemRef(): MemoryItemRef? =
            itemType.toReadSelectorItemType()?.let { MemoryItemRef(it, itemId) }
    }

    @Serializable
    private data class RejectedItem(
        @SerialName("item_type")
        val itemType: String,
        @SerialName("item_id")
        val itemId: String,
        val reason: String = "",
    ) {
        fun toItemRef(): MemoryItemRef? =
            itemType.toReadSelectorItemType()?.let { MemoryItemRef(it, itemId) }
    }
}

object PassthroughMemoryReadSelector : MemoryReadSelector {
    override suspend fun select(request: MemoryReadSelectionRequest): MemoryReadSelectionResult =
        MemoryReadSelectionResult(
            selectedHits = request.candidateHits,
            summary = "Selector disabled; using candidate order.",
        )
}

private fun MemoryRetrievalBudget.renderForReadSelector(): String =
    "claims=$claims notes=$notes tasks=$tasks sources=$sources episodes=$episodes"

private fun MemoryReadRequest.targetTextForReadSelector(): String {
    val target = threadContext.messages.firstOrNull { it.id == threadContext.targetMessageId }
        ?: return ""

    return target.content.mapNotNull { item ->
        when (item) {
            is Conversation.Message.ContentItem.UserMessage -> item.text
            is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
            is Conversation.Message.ContentItem.ToolCall -> "Tool call: ${item.call.name}"
            is Conversation.Message.ContentItem.ToolResult -> "Tool result: ${item.toolName} error=${item.isError}"
            is Conversation.Message.ContentItem.System -> "[${item.level.name}] ${item.content}"
            is Conversation.Message.ContentItem.ImageItem -> "[image:${item.source.type}]"
            is Conversation.Message.ContentItem.UnknownJson -> item.json.toString()
            is Conversation.Message.ContentItem.Thinking -> null
        }
    }.joinToString("\n").trim()
}

private fun MemoryStore.SearchHit.toReadSelectorItemRef(): MemoryItemRef =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> MemoryItemRef(MemoryItemRef.Type.SOURCE, source.id.value)
        is MemoryStore.SearchHit.EntityHit -> MemoryItemRef(MemoryItemRef.Type.ENTITY, entity.id.value)
        is MemoryStore.SearchHit.ClaimHit -> MemoryItemRef(MemoryItemRef.Type.CLAIM, claim.id.value)
        is MemoryStore.SearchHit.NoteHit -> MemoryItemRef(MemoryItemRef.Type.NOTE, note.id.value)
        is MemoryStore.SearchHit.TaskHit -> MemoryItemRef(MemoryItemRef.Type.TASK, task.id.value)
        is MemoryStore.SearchHit.ProfileHit -> MemoryItemRef(MemoryItemRef.Type.PROFILE, profile.id.value)
        is MemoryStore.SearchHit.EpisodeHit -> MemoryItemRef(MemoryItemRef.Type.EPISODE, episode.id.value)
        is MemoryStore.SearchHit.RunHit -> MemoryItemRef(MemoryItemRef.Type.RUN, run.id.value)
    }

private fun String.toReadSelectorItemType(): MemoryItemRef.Type? =
    when (trim().lowercase()) {
        "source" -> MemoryItemRef.Type.SOURCE
        "entity" -> MemoryItemRef.Type.ENTITY
        "claim" -> MemoryItemRef.Type.CLAIM
        "note" -> MemoryItemRef.Type.NOTE
        "task" -> MemoryItemRef.Type.TASK
        "profile" -> MemoryItemRef.Type.PROFILE
        "episode" -> MemoryItemRef.Type.EPISODE
        "run" -> MemoryItemRef.Type.RUN
        else -> null
    }

private fun String.limitForReadSelectorPrompt(maxChars: Int): String {
    val normalized = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars) + "...[truncated ${normalized.length - maxChars} chars]"
}

private fun String.oneLineForReadSelectorLog(maxChars: Int): String =
    limitForReadSelectorPrompt(maxChars)
