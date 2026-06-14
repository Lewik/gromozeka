package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionResult
import com.gromozeka.domain.model.memory.MemoryReadSelector
import com.gromozeka.domain.model.memory.MemoryReadSelectorTrace
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
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

        if (request.candidateHits.size <= request.plan.readSelectorDirectFinalCandidateLimit()) {
            val result = selectBatch(
                request = request,
                batchLabel = null,
                passMode = ReadSelectorPassMode.FINAL_SELECTION,
            )
            val finalSafetyAddedHits = request.finalSelectionSafetyAddedHits(result.selectedHits)
            val finalSelectedHits = (result.selectedHits + finalSafetyAddedHits)
                .distinctBy { it.toReadSelectorItemRef() }
            return result.copy(
                selectedHits = finalSelectedHits,
                summary = result.summary.withFinalSafetySummary(finalSafetyAddedHits),
                selectorTrace = MemoryReadSelectorTrace(
                    initialCandidateCount = request.candidateHits.size,
                    finalCandidateCount = request.candidateHits.size,
                    selectedCount = finalSelectedHits.size,
                    stages = listOf(
                        readSelectorTraceStage(
                            mode = MemoryReadSelectorTrace.Mode.FINAL_SELECTION,
                            level = 1,
                            batchIndex = 1,
                            batchCount = 1,
                            inputHits = request.candidateHits,
                            llmSelectedHits = result.selectedHits,
                            llmCarriedHits = emptyList(),
                            safetyAddedHits = finalSafetyAddedHits,
                            outputHits = finalSelectedHits,
                        )
                    ),
                ),
            )
        }

        return selectHierarchically(request)
    }

    private suspend fun selectHierarchically(
        request: MemoryReadSelectionRequest,
    ): MemoryReadSelectionResult {
        val originalOrder = request.candidateHits
            .mapIndexed { index, hit -> hit.toReadSelectorItemRef() to index }
            .toMap()
        var survivors = request.candidateHits
        var level = 1
        val levelSummaries = mutableListOf<String>()
        val traceStages = mutableListOf<MemoryReadSelectorTrace.Stage>()

        while (survivors.size > request.plan.readSelectorDirectFinalCandidateLimit()) {
            val candidateBatchSize = request.plan.readSelectorCandidateBatchSize()
            val candidateBatches = survivors.chunked(candidateBatchSize)
            log.info {
                "Memory read selector hierarchical level start: namespace=${request.readRequest.namespace.value} " +
                    "level=$level candidates=${survivors.size} batches=${candidateBatches.size} batchSize=$candidateBatchSize"
            }

            val nextSurvivors = candidateBatches.flatMapIndexed { index, batch ->
                val batchResult = selectBatch(
                    request = request.copy(candidateHits = batch),
                    batchLabel = "level=$level batch=${index + 1}/${candidateBatches.size}",
                    passMode = ReadSelectorPassMode.INTERMEDIATE_RECALL,
                )
                val selectedSurvivors = if (request.plan.coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) {
                    batchResult.selectedHits
                } else {
                    batchResult.selectedHits.take(READ_SELECTOR_INTERMEDIATE_LLM_SURVIVORS_PER_BATCH)
                }
                val safetySurvivors = batch.readSelectorSafetySurvivors(request.plan)
                val selectedSurvivorRefs = selectedSurvivors.mapTo(mutableSetOf()) { it.toReadSelectorItemRef() }
                val safetyAddedSurvivors = safetySurvivors
                    .filterNot { it.toReadSelectorItemRef() in selectedSurvivorRefs }
                val batchSurvivors = (selectedSurvivors + safetySurvivors)
                    .distinctBy { it.toReadSelectorItemRef() }
                traceStages += readSelectorTraceStage(
                    mode = MemoryReadSelectorTrace.Mode.INTERMEDIATE_RECALL,
                    level = level,
                    batchIndex = index + 1,
                    batchCount = candidateBatches.size,
                    inputHits = batch,
                    llmSelectedHits = batchResult.selectedHits,
                    llmCarriedHits = selectedSurvivors,
                    safetyAddedHits = safetyAddedSurvivors,
                    outputHits = batchSurvivors,
                )
                log.info {
                    "Memory read selector hierarchical batch result: namespace=${request.readRequest.namespace.value} " +
                        "level=$level batch=${index + 1}/${candidateBatches.size} candidates=${batch.size} " +
                        "llmSelected=${batchResult.selectedHits.size} carriedByLlm=${selectedSurvivors.size} " +
                        "carriedBySafety=${safetySurvivors.size} survivors=${batchSurvivors.size}"
                }
                batchSurvivors
            }
                .distinctBy { it.toReadSelectorItemRef() }
                .sortedBy { originalOrder[it.toReadSelectorItemRef()] ?: Int.MAX_VALUE }

            levelSummaries += "level $level: ${survivors.size}->${nextSurvivors.size}"
            log.info {
                "Memory read selector hierarchical level completed: namespace=${request.readRequest.namespace.value} " +
                    "level=$level candidates=${survivors.size} survivors=${nextSurvivors.size}"
            }

            if (nextSurvivors.size >= survivors.size) {
                log.info {
                    "Memory read selector hierarchical reduction stopped: namespace=${request.readRequest.namespace.value} " +
                        "level=$level candidates=${survivors.size} survivors=${nextSurvivors.size}"
                }
                survivors = nextSurvivors.take(request.plan.readSelectorHardFinalSurvivorLimit())
                levelSummaries += "hard cap to ${survivors.size}"
                break
            }

            survivors = nextSurvivors
            level += 1
        }

        val finalResult = selectBatch(
            request = request.copy(candidateHits = survivors),
            batchLabel = "final candidates=${survivors.size} levels=${levelSummaries.size}",
            passMode = ReadSelectorPassMode.FINAL_SELECTION,
        )
        val finalRequest = request.copy(candidateHits = survivors)
        val finalSafetyAddedHits = finalRequest.finalSelectionSafetyAddedHits(finalResult.selectedHits)
        val finalSelectedHits = (finalResult.selectedHits + finalSafetyAddedHits)
            .distinctBy { it.toReadSelectorItemRef() }
        traceStages += readSelectorTraceStage(
            mode = MemoryReadSelectorTrace.Mode.FINAL_SELECTION,
            level = level,
            batchIndex = 1,
            batchCount = 1,
            inputHits = survivors,
            llmSelectedHits = finalResult.selectedHits,
            llmCarriedHits = emptyList(),
            safetyAddedHits = finalSafetyAddedHits,
            outputHits = finalSelectedHits,
        )

        log.info {
            "Memory read selector hierarchical completed: namespace=${request.readRequest.namespace.value} " +
                "initialCandidates=${request.candidateHits.size} finalCandidates=${survivors.size} selected=${finalSelectedHits.size} " +
                "levels=${levelSummaries.joinToString(";")}"
        }

        return finalResult.copy(
            selectedHits = finalSelectedHits,
            summary = "Hierarchical selector ${levelSummaries.joinToString("; ")}. Final: ${finalResult.summary.withFinalSafetySummary(finalSafetyAddedHits)}",
            selectorTrace = MemoryReadSelectorTrace(
                initialCandidateCount = request.candidateHits.size,
                finalCandidateCount = survivors.size,
                selectedCount = finalSelectedHits.size,
                stages = traceStages,
            ),
        )
    }

    private suspend fun selectBatch(
        request: MemoryReadSelectionRequest,
        batchLabel: String?,
        passMode: ReadSelectorPassMode,
    ): MemoryReadSelectionResult {
        val renderedCandidates = MemoryReadSelectorCandidateRenderer.render(
            hits = request.candidateHits,
            snapshot = request.snapshot,
            query = request.sourceCandidateQuery(),
        )
        val stageMessages = request.readRequest.toMemoryStageMessages(
            stageName = "read-selector-reranker",
            taskPrompt = buildSelectorPrompt(request, renderedCandidates, passMode),
        )
        val logSuffix = batchLabel?.let { " $it" }.orEmpty()

        log.info {
            "Memory read selector LLM call: namespace=${request.readRequest.namespace.value} " +
                "candidates=${request.candidateHits.size} answerMode=${request.plan.answerMode.name} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size} stageMessages=${stageMessages.size}$logSuffix"
        }
        log.info {
            "Memory read selector candidates rendered: namespace=${request.readRequest.namespace.value} " +
                "chars=${renderedCandidates.length}$logSuffix preview=${renderedCandidates.oneLineForReadSelectorLog(8_000)}"
        }

        val hitsByRef = request.candidateHits.associateBy { it.toReadSelectorItemRef() }
        val result = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.READ_SELECTOR_OUTPUT,
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
            logContext = "namespace=${request.readRequest.namespace.value}$logSuffix",
            parse = { json.decodeFromString<ReadSelectorResponse>(it) },
        )

        log.info {
            "Memory read selector raw response: namespace=${request.readRequest.namespace.value} chars=${result.rawText.length} " +
                "$logSuffix response=${result.rawText.oneLineForReadSelectorLog(4_000)}"
        }

        val selectorResponse = result.value
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
                "$logSuffix summary=${selectorResponse.summary.oneLineForReadSelectorLog(500)}"
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
        passMode: ReadSelectorPassMode,
    ): String {
        val selectionRule = if (request.plan.coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) {
            "Select the complete relevant set: every distinct candidate that may represent a separate matching item, event, assignment, source, or ordered fact needed to avoid an incomplete answer."
        } else {
            passMode.selectionRule
        }

        return """
        Memory stage: ReadSelectorReranker v1.
        Namespace: ${request.readRequest.namespace.value}
        Pass mode: ${passMode.promptLabel}

        Goal:
        ${passMode.goalText}

        Selection rules:
        ${passMode.extraRules(request.plan)}
        - $selectionRule
        - Candidate memory items are JSON records. Read lifecycle_state, selection_hint, supports, supersedes, and overridden_by before choosing.
        - Reject candidates that only have vague lexical overlap.
        - Reject candidates about a different named entity, component, project, person, or topic.
        - Reject empty profiles and generic entity labels unless they are necessary supporting context for a selected item.
        - Prefer active claims for factual answers when they directly answer and are not incomplete, stale, or contradicted by newer active typed context.
        - Prefer episodes for reusable lessons and "what did we learn" questions.
        - Prefer notes for rationale, decisions, plans, contextual meaning, and summarized factual details that do not have a complete current claim.
        - Prefer action items only for open commitments or workflow state.
        - Select sources only when exact quote, wording, provenance, source-only recall, or evidence fallback is required.
        - For advice, improvement, troubleshooting, or "how should I do better" questions, select concrete user-specific successes, failures, constraints, goals, preferences, and prior attempts. Do not drop a specific successful/failed example merely because a broader experience claim was selected.
        - When Planned coverage mode is COMPLETE_SET, select every relevant distinct typed memory item and every required evidence source that may contain a separate missing item. Do not collapse to the first good match.
        - For count, list, "how many", and COMPLETE_SET questions, do not require exact target wording. Select plausible action, lifecycle, ownership, ordering, delivery, repair, completion, attendance, and status variants when they may contribute a separate answer item.
        - For multi-hop temporal questions, keep complementary facts together: event dates, question dates, relative offsets, lead times, durations, and sources with exact timing wording. Do not select only the candidate that contains the last lexical hop.
        - When one candidate gives a relative time anchored to another event, select the anchor event timing candidate too. A lead time or offset alone is not sufficient for "when" or "how long ago" questions.
        - For relative-time arithmetic, a selected set is insufficient unless it includes every explicit anchor needed to compute the answer. Rejecting an event-date or question-date anchor as "not needed" is incorrect when another selected candidate only gives a lead time, offset, or duration.
        - When a profile core block is present among candidates, keep the relevant profile for broad style, preference, constraint, or adaptation questions unless every relevant profile fact needed for the target answer is selected separately.
        - A single specific style claim is not sufficient for a broad adaptation question when the relevant profile also contains language, tone, formatting, or answer-detail preferences.
        - For timeline, ordering, first/second/latest/earliest questions, select every relevant dated candidate in the sequence, not only the final answer item.
        - When Planned answer mode is FACTUAL or ACTION_ITEM and Require evidence fallback is false, prefer active typed memory over source candidates.
        - When candidates disagree about a current/usual value, schedule, time, preference, or constraint, select enough candidates to expose the conflict and prefer the newer relevant active typed memory or its supporting source evidence.
        - Raw sources are evidence, not current truth. Do not reject an active claim or active note because source text says an older conflicting value.
        - Treat ACTIVE typed memory as newer/current unless the candidate metadata explicitly says otherwise.
        - For current-state answers, never use stale, superseded, retracted, expired, or candidate memory as stronger evidence than any relevant ACTIVE memory.
        - For current-state answers at a later target date, keep later dated plans, intentions, or scheduled changes that may have become the current state by that date. Select them together with any older current-state candidate so the answer can reason about whether the old state is stale.
        - For questions about an initial, original, previous, older, or before/after state, non-current typed memory can be the direct historical answer. Select both the older candidate and the current replacement when the answer depends on comparing old vs current state.
        - For target questions about a current named metric, record, score, benchmark, quota, threshold, or personal best, prefer a direct current metric/record typed claim over a contextual historical event or goal claim. Historical events answer when the event happened; current metric claims answer what the remembered value is.
        - Do not reject a direct current metric/record claim merely because its normalized text uses a broader metric label than the target wording when its context, scope, or evidence binds it to the same named domain.
        - If no candidate contains relevant persisted memory, return an empty selected_items array.

        Planned answer mode: ${request.plan.answerMode.name}
        Planned coverage mode: ${request.plan.coverageMode.name}
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
              "item_type": "claim | note | action_item | profile | source | episode | entity | run",
              "item_id": "exact candidate id",
              "rank": 1,
              "relevance": "direct_answer | supporting_context | required_evidence",
              "reason": "short reason"
            }
          ],
          "rejected_items": [
            {
              "item_type": "claim | note | action_item | profile | source | episode | entity | run",
              "item_id": "exact candidate id",
              "reason": "short reason"
            }
          ],
          "summary": "one short sentence"
        }
    """.trimIndent()
    }

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

private enum class ReadSelectorPassMode(
    val promptLabel: String,
    val goalText: String,
    val selectionRule: String,
) {
    INTERMEDIATE_RECALL(
        promptLabel = "intermediate_recall",
        goalText = "Preserve candidates that may be useful for a later global selector. This pass is recall-oriented, not final.",
        selectionRule = "Select a compact survivor set, but prefer keeping a plausible candidate over dropping it too early.",
    ) {
        override fun extraRules(plan: MemoryReadPlan): String = """
        - This is an intermediate pass over one candidate batch. A later global selector will rerank survivors from all batches.
        - When uncertain, select the candidate so the final pass can compare it globally.
        - Reject only candidates that are clearly irrelevant, stale compared to stronger active memory, or pure lexical noise. Do not treat an ACTIVE pending obligation as stale solely because its source is old; completion, cancellation, supersession, or a stronger contradictory active memory must be explicit.
        - Aim for at most ${plan.readSelectorIntermediateLlmSurvivorLimit()} selected_items. Exceed that when many distinct dated facts or complete-set candidates are required together.
        """.trimIndent()
    },
    FINAL_SELECTION(
        promptLabel = "final_selection",
        goalText = "Select and rerank only the persisted memory candidates that are actually useful for answering TARGET_MESSAGE.",
        selectionRule = "Select the smallest sufficient set.",
    ) {
        override fun extraRules(plan: MemoryReadPlan): String =
            if (plan.coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) {
                "- This is the final global pass. Prefer precision only after the full relevant set is covered; do not drop a plausible distinct answer item merely because another item is stronger."
            } else {
                "- This is the final global pass. Prefer precision over keeping marginal context."
            }
    };

    abstract fun extraRules(plan: MemoryReadPlan): String
}

object PassthroughMemoryReadSelector : MemoryReadSelector {
    override suspend fun select(request: MemoryReadSelectionRequest): MemoryReadSelectionResult =
        MemoryReadSelectionResult(
            selectedHits = request.candidateHits,
            summary = "Selector disabled; using candidate order.",
        )
}

private fun MemoryRetrievalBudget.renderForReadSelector(): String =
    "claims=$claims notes=$notes actionItems=$actionItems sources=$sources episodes=$episodes"

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

private fun MemoryReadSelectionRequest.sourceCandidateQuery(): String =
    buildList {
        plan.retrievalRequests
            .map { it.query }
            .distinct()
            .forEach(::add)
        add(readRequest.targetTextForReadSelector())
    }.joinToString("\n").trim()

private fun MemoryStore.SearchHit.toReadSelectorItemRef(): MemoryItemRef =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> MemoryItemRef(MemoryItemRef.Type.SOURCE, source.id.value)
        is MemoryStore.SearchHit.EntityHit -> MemoryItemRef(MemoryItemRef.Type.ENTITY, entity.id.value)
        is MemoryStore.SearchHit.ClaimHit -> MemoryItemRef(MemoryItemRef.Type.CLAIM, claim.id.value)
        is MemoryStore.SearchHit.NoteHit -> MemoryItemRef(MemoryItemRef.Type.NOTE, note.id.value)
        is MemoryStore.SearchHit.ActionItemHit -> MemoryItemRef(MemoryItemRef.Type.ACTION_ITEM, actionItem.id.value)
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
        "action_item", "actionItem" -> MemoryItemRef.Type.ACTION_ITEM
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

private fun List<MemoryStore.SearchHit>.readSelectorSafetySurvivors(plan: MemoryReadPlan): List<MemoryStore.SearchHit> {
    val scoreLeaders = sortedByReadSelectorStrength().take(plan.readSelectorScoreSafetyLimit())
    val activeTypedLeaders = filter { it.isActiveTypedMemory() }
        .sortedByReadSelectorStrength()
        .take(plan.readSelectorActiveTypedSafetyLimit())
    val profileLeaders = if (MemoryReadPlan.CoreBlock.PROFILE in plan.coreBlocks) {
        filterIsInstance<MemoryStore.SearchHit.ProfileHit>()
            .sortedByReadSelectorStrength()
            .take(1)
    } else {
        emptyList()
    }
    val evidenceLeaders = if (plan.readSelectorEvidenceSafetyLimit() > 0) {
        filterIsInstance<MemoryStore.SearchHit.SourceHit>()
            .sortedByReadSelectorStrength()
            .take(plan.readSelectorEvidenceSafetyLimit())
    } else {
        emptyList()
    }
    val modeLeaders = when (plan.answerMode) {
        MemoryReadPlan.AnswerMode.RATIONALE -> filterIsInstance<MemoryStore.SearchHit.NoteHit>() +
            filterIsInstance<MemoryStore.SearchHit.EpisodeHit>()
        MemoryReadPlan.AnswerMode.ACTION_ITEM -> filterIsInstance<MemoryStore.SearchHit.ActionItemHit>()
        else -> emptyList()
    }
        .sortedByReadSelectorStrength()
        .take(1)

    return (scoreLeaders + activeTypedLeaders + profileLeaders + evidenceLeaders + modeLeaders)
        .distinctBy { it.toReadSelectorItemRef() }
        .take(plan.readSelectorSafetySurvivorLimit())
}

private fun MemoryReadSelectionRequest.finalSelectionSafetyAddedHits(
    selectedHits: List<MemoryStore.SearchHit>,
): List<MemoryStore.SearchHit> {
    if (plan.coverageMode != MemoryReadPlan.CoverageMode.COMPLETE_SET) return emptyList()

    val selectedRefs = selectedHits.mapTo(mutableSetOf()) { it.toReadSelectorItemRef() }
    return candidateHits
        .readSelectorSafetySurvivors(plan)
        .filterNot { it.toReadSelectorItemRef() in selectedRefs }
}

private fun String.withFinalSafetySummary(safetyAddedHits: List<MemoryStore.SearchHit>): String =
    if (safetyAddedHits.isEmpty()) {
        this
    } else {
        "$this Final safety added ${safetyAddedHits.size} complete-set survivor(s)."
    }

private fun MemoryReadPlan.readSelectorSafetySurvivorLimit(): Int =
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) {
        READ_SELECTOR_COMPLETE_SET_SAFETY_SURVIVORS_PER_BATCH
    } else {
        READ_SELECTOR_SAFETY_SURVIVORS_PER_BATCH
    }

private fun MemoryReadPlan.readSelectorScoreSafetyLimit(): Int =
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) 4 else READ_SELECTOR_SCORE_SAFETY_PER_BATCH

private fun MemoryReadPlan.readSelectorActiveTypedSafetyLimit(): Int =
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) 12 else READ_SELECTOR_ACTIVE_TYPED_SAFETY_PER_BATCH

private fun MemoryReadPlan.readSelectorEvidenceSafetyLimit(): Int =
    when {
        coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET -> (retrievalBudget.sources.takeIf { it > 0 } ?: 3)
        requireEvidenceFallback -> 1
        else -> 0
    }

private fun MemoryReadPlan.readSelectorHardFinalSurvivorLimit(): Int =
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) {
        READ_SELECTOR_COMPLETE_SET_HARD_FINAL_SURVIVOR_LIMIT
    } else {
        READ_SELECTOR_HARD_FINAL_SURVIVOR_LIMIT
    }

private fun MemoryReadPlan.readSelectorDirectFinalCandidateLimit(): Int =
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) {
        READ_SELECTOR_COMPLETE_SET_DIRECT_FINAL_CANDIDATE_LIMIT
    } else {
        READ_SELECTOR_CANDIDATE_BATCH_SIZE
    }

private fun MemoryReadPlan.readSelectorCandidateBatchSize(): Int =
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) {
        READ_SELECTOR_COMPLETE_SET_CANDIDATE_BATCH_SIZE
    } else {
        READ_SELECTOR_CANDIDATE_BATCH_SIZE
    }

private fun MemoryReadPlan.readSelectorIntermediateLlmSurvivorLimit(): Int =
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) {
        READ_SELECTOR_COMPLETE_SET_INTERMEDIATE_LLM_SURVIVORS_PER_BATCH
    } else {
        READ_SELECTOR_INTERMEDIATE_LLM_SURVIVORS_PER_BATCH
    }

private fun readSelectorTraceStage(
    mode: MemoryReadSelectorTrace.Mode,
    level: Int,
    batchIndex: Int,
    batchCount: Int,
    inputHits: List<MemoryStore.SearchHit>,
    llmSelectedHits: List<MemoryStore.SearchHit>,
    llmCarriedHits: List<MemoryStore.SearchHit>,
    safetyAddedHits: List<MemoryStore.SearchHit>,
    outputHits: List<MemoryStore.SearchHit>,
): MemoryReadSelectorTrace.Stage =
    MemoryReadSelectorTrace.Stage(
        mode = mode,
        level = level,
        batchIndex = batchIndex,
        batchCount = batchCount,
        inputCount = inputHits.size,
        llmSelectedCount = llmSelectedHits.size,
        llmCarriedCount = llmCarriedHits.size,
        safetyAddedCount = safetyAddedHits.size,
        outputCount = outputHits.size,
        inputRefs = inputHits.toReadSelectorTraceRefs(),
        llmSelectedRefs = llmSelectedHits.toReadSelectorTraceRefs(),
        llmCarriedRefs = llmCarriedHits.toReadSelectorTraceRefs(),
        safetyAddedRefs = safetyAddedHits.toReadSelectorTraceRefs(),
        outputRefs = outputHits.toReadSelectorTraceRefs(),
    )

private fun List<MemoryStore.SearchHit>.toReadSelectorTraceRefs(): List<MemoryItemRef> =
    take(READ_SELECTOR_TRACE_REF_LIMIT).map { it.toReadSelectorItemRef() }

private fun List<MemoryStore.SearchHit>.sortedByReadSelectorStrength(): List<MemoryStore.SearchHit> =
    sortedWith(
        compareByDescending<MemoryStore.SearchHit> { it.score }
            .thenByDescending { it.readSelectorImportance() }
            .thenBy { it.toReadSelectorItemRef().type.name }
            .thenBy { it.toReadSelectorItemRef().id }
    )

private fun MemoryStore.SearchHit.isActiveTypedMemory(): Boolean =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> claim.status == MemoryClaim.Status.ACTIVE && claim.archivedAt == null
        is MemoryStore.SearchHit.NoteHit -> note.status == MemoryNote.Status.ACTIVE && note.archivedAt == null
        is MemoryStore.SearchHit.ActionItemHit -> actionItem.status in setOf(MemoryActionItem.Status.OPEN, MemoryActionItem.Status.IN_PROGRESS, MemoryActionItem.Status.BLOCKED) && actionItem.archivedAt == null
        is MemoryStore.SearchHit.EpisodeHit -> episode.archivedAt == null
        is MemoryStore.SearchHit.ProfileHit -> true
        is MemoryStore.SearchHit.EntityHit -> true
        is MemoryStore.SearchHit.SourceHit,
        is MemoryStore.SearchHit.RunHit,
        -> false
    }

private fun MemoryStore.SearchHit.readSelectorImportance(): Int =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> claim.importance
        is MemoryStore.SearchHit.NoteHit -> note.importance
        is MemoryStore.SearchHit.ActionItemHit -> when (actionItem.priority) {
            MemoryActionItem.Priority.HIGH -> 9
            MemoryActionItem.Priority.NORMAL -> 6
            MemoryActionItem.Priority.LOW -> 3
        }
        is MemoryStore.SearchHit.EpisodeHit -> ((episode.successScore ?: 0.5) * 10).toInt()
        is MemoryStore.SearchHit.ProfileHit -> 7
        is MemoryStore.SearchHit.EntityHit -> 4
        is MemoryStore.SearchHit.SourceHit -> 3
        is MemoryStore.SearchHit.RunHit -> 1
    }

private const val READ_SELECTOR_CANDIDATE_BATCH_SIZE = 20
private const val READ_SELECTOR_COMPLETE_SET_DIRECT_FINAL_CANDIDATE_LIMIT = 40
private const val READ_SELECTOR_COMPLETE_SET_CANDIDATE_BATCH_SIZE = 40
private const val READ_SELECTOR_INTERMEDIATE_LLM_SURVIVORS_PER_BATCH = 8
private const val READ_SELECTOR_COMPLETE_SET_INTERMEDIATE_LLM_SURVIVORS_PER_BATCH = 16
private const val READ_SELECTOR_SAFETY_SURVIVORS_PER_BATCH = 4
private const val READ_SELECTOR_COMPLETE_SET_SAFETY_SURVIVORS_PER_BATCH = 16
private const val READ_SELECTOR_SCORE_SAFETY_PER_BATCH = 2
private const val READ_SELECTOR_ACTIVE_TYPED_SAFETY_PER_BATCH = 2
private const val READ_SELECTOR_HARD_FINAL_SURVIVOR_LIMIT = 20
private const val READ_SELECTOR_COMPLETE_SET_HARD_FINAL_SURVIVOR_LIMIT = 40
private const val READ_SELECTOR_TRACE_REF_LIMIT = 24
