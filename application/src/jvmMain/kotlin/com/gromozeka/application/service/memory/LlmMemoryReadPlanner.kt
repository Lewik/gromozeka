package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadPlanner
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class LlmMemoryReadPlanner(
    private val runtime: AiRuntime,
    private val timezone: String,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryReadPlanner {
    private val log = KLoggers.logger(this)

    override suspend fun plan(request: MemoryReadRequest): MemoryReadPlan {
        val stageMessages = request.toMemoryStageMessages(
            stageName = "read-retrieval-planner",
            taskPrompt = buildPlannerPrompt(request),
        )

        log.info {
            "Memory read planner LLM call: namespace=${request.namespace.value} " +
                "threadContext=${request.memoryThreadContextSummaryForLog()} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size} stageMessages=${stageMessages.size}"
        }

        val result = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.READ_PLANNER_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.ReadRetrievalPlanner,
                    toolContext = mapOf(
                        "memoryReadPlanner" to true,
                        "memoryNamespace" to request.namespace.value,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "read-retrieval-planner",
            logContext = "namespace=${request.namespace.value}",
            parse = {
                json.decodeFromString<ReadPlannerResponse>(it)
                    .toPlan()
                    .withCoverageGuards(request)
                    .withFactualRecallEvidenceRequests(request)
                    .withRationaleNoteRequest(request)
            },
        )

        log.info {
            "Memory read planner raw response: namespace=${request.namespace.value} chars=${result.rawText.length} " +
                "response=${result.rawText.oneLineForReadMemoryLog(4_000)}"
        }

        val plan = result.value
        if (plan.needMemory) return plan

        val verifiedPlan = verifyNoMemoryDecision(request, plan)
        if (verifiedPlan != plan) {
            log.info {
                "Memory read planner repaired: namespace=${request.namespace.value} " +
                    "reason=llm_no_memory_verifier target=${request.targetMessageText().oneLineForReadMemoryLog(200)}"
            }
        }

        return verifiedPlan
    }

    private fun buildPlannerPrompt(request: MemoryReadRequest): String = """
        Memory stage: ReadTimeRetrievalPlanner v3.
        Current time: ${Clock.System.now()}
        Timezone: $timezone
        Namespace: ${request.namespace.value}

        Stage instructions:
        $READ_TIME_RETRIEVAL_PLANNER_PROMPT

        TARGET_CONTEXT text:
        ${request.targetMessageText()}
    """.trimIndent()

    private suspend fun verifyNoMemoryDecision(
        request: MemoryReadRequest,
        originalPlan: MemoryReadPlan,
    ): MemoryReadPlan {
        val stageMessages = request.toMemoryStageMessages(
            stageName = "read-need-verifier",
            taskPrompt = buildNeedVerifierPrompt(request),
        )

        log.info {
            "Memory read need verifier LLM call: namespace=${request.namespace.value} " +
                "threadContext=${request.memoryThreadContextSummaryForLog()} stageMessages=${stageMessages.size}"
        }

        val result = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.READ_NEED_VERIFIER_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.ReadNeedVerifier,
                    toolContext = mapOf(
                        "memoryReadNeedVerifier" to true,
                        "memoryNamespace" to request.namespace.value,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "read-need-verifier",
            logContext = "namespace=${request.namespace.value}",
            parse = { json.decodeFromString<ReadNeedVerifierResponse>(it) },
        )

        log.info {
            "Memory read need verifier raw response: namespace=${request.namespace.value} chars=${result.rawText.length} " +
                "response=${result.rawText.oneLineForReadMemoryLog(2_000)}"
        }

        val verifier = result.value
        if (!verifier.needsMemory) {
            log.info {
                "Memory read need verifier accepted no-memory plan: namespace=${request.namespace.value} " +
                    "reason=${verifier.reason.oneLineForReadMemoryLog(300)}"
            }
            return originalPlan
        }

        log.info {
            "Memory read need verifier rejected no-memory plan: namespace=${request.namespace.value} " +
                "mode=${verifier.answerMode} needsSource=${verifier.needsSource} " +
                "query=${verifier.query.oneLineForReadMemoryLog(300)} reason=${verifier.reason.oneLineForReadMemoryLog(300)}"
        }
        return verifier.toFallbackPlan(request)
            .withCoverageGuards(request)
            .withFactualRecallEvidenceRequests(request)
    }

    private fun buildNeedVerifierPrompt(request: MemoryReadRequest): String = """
        Memory stage: ReadNeedVerifier v1.
        Current time: ${Clock.System.now()}
        Timezone: $timezone
        Namespace: ${request.namespace.value}

        The previous memory planner returned need_memory=false.
        Verify whether that no-memory decision is safe.

        You are NOT deciding whether the memory store contains the answer.
        You are deciding whether the assistant should search memory to enrich the target context before answering or acting.
        The target may be a question, action item, fragment, topic, phrase, or work context.

        Return JSON:
        {
          "needs_memory": true,
          "answer_mode": "factual | rationale | action_item | mixed",
          "needs_source": false,
          "query": "short memory search query",
          "reason": "short explanation"
        }

        Treat these examples as memory-dependent unless the target itself already contains the answer:
        - "what did I say about ..."
        - "when did I say ..."
        - "what database does project X use?"
        - "according to the handoff/document/prompt pack, what ..."
        - "who owns X?"
        - "what do I currently prefer?"
        - "which brand should you remember that I prefer?"
        - "if memory lacks this, say ..."
        - "what order/procedure/workflow should we follow?"
        - "what should report X show/include?"
        - "what should component X do/use/be?"
        - questions about prior user preferences, project facts, team facts, ownership, decisions, corrections, maintenance rules, reporting rules, or prior-session context.
        - requests for a short phrase can still be memory-dependent when they ask for remembered rules or prior decisions.

        Treat these as no-memory:
        - the target is fully self-contained and asks for general reasoning, coding, math, rewriting, or explanation.
        - the target asks to perform a new immediate action and does not require prior user/project/team context.
        - the target contains all facts needed to answer.

        Use needs_source=true only when exact quote, evidence, source, or wording-level grounding is needed.
        Use needs_source=true when the target asks according to, from, or in a named/pasted/imported document and the target does not include the document content.
        Use needs_source=true when the target asks to recall what was discussed, suggested, recommended, listed, or mentioned in a previous conversation/session.
        Use needs_source=true for prior assistant recommendation recall because the precise named option may exist only in raw conversation/source evidence.
        If unsure, set needs_memory=true. False negatives are worse than a small extra recall.

        TARGET_CONTEXT text:
        ${request.targetMessageText()}
    """.trimIndent()

    @Serializable
    private data class ReadPlannerResponse(
        @SerialName("need_memory")
        val needMemory: Boolean = false,
        @SerialName("answer_mode")
        val answerMode: String = "mixed",
        @SerialName("coverage_mode")
        val coverageMode: String = "minimal",
        @SerialName("core_blocks")
        val coreBlocks: List<String> = emptyList(),
        @SerialName("retrieval_budget")
        val retrievalBudget: Budget = Budget(),
        @SerialName("retrieval_requests")
        val retrievalRequests: List<Request> = emptyList(),
        @SerialName("require_evidence_fallback")
        val requireEvidenceFallback: Boolean = false,
    ) {
        fun toPlan(): MemoryReadPlan =
            MemoryReadPlan(
                needMemory = needMemory,
                answerMode = answerMode.toAnswerMode(),
                coverageMode = coverageMode.toCoverageMode(),
                coreBlocks = coreBlocks.mapNotNull { it.toCoreBlock() }.toSet(),
                retrievalBudget = retrievalBudget.toBudget(),
                retrievalRequests = retrievalRequests.mapNotNull { it.toRetrievalRequest() },
                requireEvidenceFallback = requireEvidenceFallback,
            )
    }

    @Serializable
    private data class Budget(
        val claims: Int = 0,
        val notes: Int = 0,
        @SerialName("action_items")
        val actionItems: Int = 0,
        val sources: Int = 0,
        val episodes: Int = 0,
    ) {
        fun toBudget(): MemoryRetrievalBudget =
            MemoryRetrievalBudget(
                claims = claims.coerceIn(0, 12),
                notes = notes.coerceIn(0, 8),
                actionItems = actionItems.coerceIn(0, 8),
                sources = sources.coerceIn(0, 8),
                episodes = episodes.coerceIn(0, 4),
            )
    }

    @Serializable
    private data class Request(
        @SerialName("memory_type")
        val memoryType: String,
        val why: String = "",
        val query: String,
        @SerialName("top_k")
        val topK: Int = 0,
        val filters: JsonObject = JsonObject(emptyMap()),
        @SerialName("preferred_claim_predicates")
        val preferredClaimPredicates: List<String> = emptyList(),
        @SerialName("deprioritized_claim_predicates")
        val deprioritizedClaimPredicates: List<String> = emptyList(),
    ) {
        fun toRetrievalRequest(): MemoryReadPlan.RetrievalRequest? {
            val semanticType = memoryType.toMemorySemanticType() ?: return null
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) return null

            return MemoryReadPlan.RetrievalRequest(
                memoryType = semanticType,
                why = why.trim(),
                query = cleanQuery,
                topK = topK.coerceIn(0, 12),
                filters = filters,
                preferredClaimPredicates = preferredClaimPredicates.map { it.trim() }.filter { it.isNotBlank() },
                deprioritizedClaimPredicates = deprioritizedClaimPredicates.map { it.trim() }.filter { it.isNotBlank() },
            )
        }
    }

    @Serializable
    private data class ReadNeedVerifierResponse(
        @SerialName("needs_memory")
        val needsMemory: Boolean = false,
        @SerialName("answer_mode")
        val answerMode: String = "factual",
        @SerialName("needs_source")
        val needsSource: Boolean = false,
        val query: String = "",
        val reason: String = "",
    ) {
        fun toFallbackPlan(request: MemoryReadRequest): MemoryReadPlan {
            val searchQuery = query.trim().ifBlank { request.targetMessageText().trim() }
            return MemoryReadPlan(
                needMemory = true,
                answerMode = answerMode.toAnswerMode(),
                coreBlocks = emptySet(),
                retrievalBudget = MemoryRetrievalBudget(
                    claims = 4,
                    notes = 1,
                    actionItems = 1,
                    sources = if (needsSource) 2 else 0,
                    episodes = 1,
                ),
                retrievalRequests = buildList {
                    add(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "LLM verifier rejected a no-memory read plan: ${reason.trim()}",
                            query = searchQuery,
                            topK = 4,
                            preferredClaimPredicates = emptyList(),
                            deprioritizedClaimPredicates = emptyList(),
                        )
                    )
                    add(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.NOTE,
                            why = "Check concise remembered context when no exact claim exists.",
                            query = searchQuery,
                            topK = 1,
                            preferredClaimPredicates = emptyList(),
                            deprioritizedClaimPredicates = emptyList(),
                        )
                    )
                    add(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.ACTION_ITEM,
                            why = "Check active commitments if the target asks about ownership or follow-up state.",
                            query = searchQuery,
                            topK = 1,
                            preferredClaimPredicates = emptyList(),
                            deprioritizedClaimPredicates = emptyList(),
                        )
                    )
                    if (needsSource) {
                        add(
                            MemoryReadPlan.RetrievalRequest(
                                memoryType = MemorySemanticType.SOURCE,
                                why = "Ground exact quote or evidence-style recall.",
                                query = searchQuery,
                                topK = 2,
                                preferredClaimPredicates = emptyList(),
                                deprioritizedClaimPredicates = emptyList(),
                            )
                        )
                    }
                },
                requireEvidenceFallback = needsSource,
            )
        }
    }

    private companion object {
        val READ_TIME_RETRIEVAL_PLANNER_PROMPT = """
            You are ReadTimeRetrievalPlanner v3.

            Goal:
            Plan memory retrieval that enriches the target context before answer synthesis.
            The target may be a question, action item, fragment, topic, phrase, or work context.
            Do not answer the target. Return only the retrieval plan.

            Return JSON:
            {
              "need_memory": true,
              "answer_mode": "factual | rationale | action_item | mixed",
              "coverage_mode": "minimal | complete_set",
              "core_blocks": ["profile", "action_items"],
              "retrieval_budget": {
                "claims": 0,
                "notes": 0,
                "action_items": 0,
                "sources": 0,
                "episodes": 0
              },
              "retrieval_requests": [
                {
                  "memory_type": "claim | note | action_item | source | profile | episode",
                  "why": "short explanation",
                  "query": "text",
                  "top_k": 0,
                  "filters": {},
                  "preferred_claim_predicates": [],
                  "deprioritized_claim_predicates": []
                }
              ],
              "require_evidence_fallback": true
            }

            Rules:
            - Use the full conversation only to understand the target context.
            - Plan retrieval only for the target context/current turn, not for older turns.
            - Use "factual" for preferences, status, or direct fact recall.
            - Use "rationale" for why/how/what-we-discussed questions.
            - For "rationale" answers, retrieve notes first. Rationale, design direction, trade-offs, and "what did we decide/how did we decide/why did we choose" questions should include at least one note retrieval request unless the target message already contains the answer.
            - Use episode retrieval for prior attempts, reusable lessons, situation-action-result outcomes, "what did we learn", and "what worked last time" questions.
            - For a specific named component or a narrowly described reusable lesson, use episode top_k=1. Use top_k=2 only for broad "what lessons do we have" style questions.
            - Use "action_item" for internal commitments and follow-ups.
            - For action item status, done, cancelled, closed, blocked, or "is anything still open" questions, retrieve action item memory even when the relevant action item may no longer be open.
            - Use "mixed" when multiple memory classes are required.
            - Use coverage_mode="minimal" when one or a few directly relevant items are sufficient.
            - Use coverage_mode="complete_set" when omission changes the answer: counts, inventories, list-all/set questions, timeline/order reconstruction, or requests that need every distinct matching item/event/assignment/source.
            - Prefer no memory when the current request is fully self-contained.
            - A request is not self-contained when it asks about user-specific, project-specific, team-specific, or prior-session context and the target message does not contain the answer.
            - Include the profile core block for broad user/project working style, language, tone, preferences, constraints, and "how should you adapt to me/us" questions.
            - For adaptation/profile questions involving both the user and a project, retrieve profile memory broadly enough to include both user-level and project-level preferences.
            - For named local projects, repositories, products, agents, user preferences, or working agreements, plan memory retrieval instead of relying on model world knowledge.
            - If the target asks according to, from, or in a named/pasted/imported document, handoff, prompt pack, architecture document, or data model, plan memory retrieval unless the target message includes the relevant document content.
            - For named document questions that ask for exact definitions, fields, listed items, or specific component roles, include source retrieval; notes are useful for document digests and rationale, but raw source can be the best answer evidence.
            - If the target asks what order, procedure, workflow, policy, or working agreement "we should follow", and the target itself does not provide the steps, retrieve memory even if the wording sounds like a general advice question.
            - If the target asks what a named report, trace, pipeline, component, policy, rule, or workflow should show/include/do/use/be, retrieve memory unless the target itself provides that rule.
            - For timeline, sequence, ordering, ordinal, "first/second/third/latest/previous/next", or "what happened before/after" questions, use coverage_mode="complete_set" and retrieve enough surrounding ordered items to establish the order. Do not set the claim budget/top_k equal to the requested ordinal only; use at least 4 claims when available, or source retrieval if the sequence likely lives in one document/source.
            - For temporal recall questions such as "how many days ago", "when did I", "what date", or "what day", retrieve direct claims first and include source retrieval as evidence fallback because dated personal events may be preserved as source-only evidence.
            - For relative-duration questions that name an anchor event, such as "how many days/weeks/months ago did X when/at the time Y", retrieve the dated fact for X and the dated anchor event Y. Use coverage_mode="complete_set" because the question date alone may be the wrong endpoint.
            - For questions asking how long the user had been doing an activity when an anchor event happened, retrieve explicit start/begin/first-participation evidence for that activity and the anchor event. A duration-as-of claim can be useful conflict context, but it is not a substitute for the start-date operand.
            - For factual questions about current/usual preferences, constraints, schedules, routines, times, or remembered values, retrieve claims and notes. Add source evidence when a conflict, update, stale typed fact, or exact value is possible.
            - Notes may contain exact factual context when extraction chose not to materialize a separate claim. Do not make factual recall claim-only when the target asks for a current/usual value, schedule, time, preference, or constraint.
            - For count, set, inventory, list-all, or "how many" questions, use coverage_mode="complete_set", retrieve enough typed facts to enumerate the set, and include source retrieval as evidence fallback. Counts are wrong if one item is omitted.
            - For remembered workflows, retrieve claim and note memory first. Retrieve source memory only when exact wording, provenance, conflict, or evidence fallback is required.
            - For claim retrieval, set preferred_claim_predicates to predicates that directly answer the target and deprioritized_claim_predicates to contextual predicates that may be useful but should not outrank direct answers.
            - For questions about historical measured values, durations, scores, costs, playtimes, completion times, or totals across observed measurements, prefer "metric_observation" claims and use coverage_mode="complete_set" when omission changes the answer.
            - For questions about a current named metric, current total, latest value, record, score, benchmark, quota, threshold, or personal best, prefer "current_metric_value" claims and deprioritize contextual event/goal claims unless the target asks for the event or goal itself.
            - For questions asking for an increase, decrease, change, delta, difference, gain, loss, or net movement in a metric, retrieve every explicit operand needed for the calculation: baseline/previous value, later/current/final value, and explicit aggregate_increase or aggregate_decrease claims when present. Use coverage_mode="complete_set" because omitting one operand changes the answer.
            - For questions about where a person, user, organization, project, or concrete subject is currently located, lives, is based, or ended up after relocation, prefer "current_location" claims and deprioritize historical move/event claims unless the target asks for the history itself.
            - For questions about what a past generated story, image description, draft, specification, or artifact said/showed/included, prefer "generated_artifact_detail" claims and include source retrieval as evidence fallback when exact wording matters.
            - For questions asking to recall what was discussed, suggested, recommended, listed, or mentioned in a previous conversation/session, include source retrieval as evidence fallback. Broad claims or notes may preserve the topic while the precise named option exists only in raw conversation/source evidence.
            - For prior assistant-generated recommendations, ranked lists, itineraries, or option lists, retrieve source evidence when the target asks for the specific item that matched a clue.
            - Leave predicate priority arrays empty when no predicate ranking is needed.
            - Keep retrieval bounded.
            - Include source retrieval when conflicts, uncertainty, or quotation-quality grounding is needed.
            - Include source retrieval when the target asks what the user said, what wording was used, or asks about a weak/uncertain observation that may intentionally exist only as source memory.
            - For ordinary factual/action item answers, especially when the target asks for "only" a value or action item title, set require_evidence_fallback=false and sources=0 unless exact quote/source/provenance is explicitly requested.
            - Prefer note retrieval over raw source retrieval for rationale questions. Use sources as evidence fallback, not as the primary rationale memory.
            - Return valid JSON only.
        """.trimIndent()
    }
}

private fun MemoryReadPlan.withRationaleNoteRequest(request: MemoryReadRequest): MemoryReadPlan {
    if (!needMemory || answerMode != MemoryReadPlan.AnswerMode.RATIONALE) {
        return this
    }
    if (retrievalRequests.any { it.memoryType == MemorySemanticType.NOTE }) {
        return this
    }

    val query = request.targetMessageText().ifBlank { "rationale decision discussion" }
    return copy(
        retrievalBudget = retrievalBudget.copy(
            notes = retrievalBudget.notes.takeIf { it > 0 } ?: 4,
        ),
        retrievalRequests = listOf(
            MemoryReadPlan.RetrievalRequest(
                memoryType = MemorySemanticType.NOTE,
                why = "Rationale answer mode requires note-first retrieval.",
                query = query,
                topK = retrievalBudget.notes.takeIf { it > 0 } ?: 4,
            )
        ) + retrievalRequests,
    )
}

private fun MemoryReadPlan.withCoverageGuards(request: MemoryReadRequest): MemoryReadPlan {
    if (!needMemory) return this

    val target = request.targetMessageText()
    val needsCompleteSet = target.requiresCompleteSetCoverage()
    val needsTemporalEvidence = target.requiresTemporalEvidenceFallback()
    if (!needsCompleteSet && !needsTemporalEvidence) return this

    val guardedBudget = retrievalBudget.copy(
        claims = retrievalBudget.claims.coerceAtLeast(if (needsCompleteSet) 8 else 4),
        sources = retrievalBudget.sources.coerceAtLeast(if (needsTemporalEvidence || needsCompleteSet) 2 else 0),
    )
    val hasSourceRequest = retrievalRequests.any { it.memoryType == MemorySemanticType.SOURCE }
    val guardedRequests = if (hasSourceRequest || (!needsTemporalEvidence && !needsCompleteSet)) {
        retrievalRequests
    } else {
        retrievalRequests + MemoryReadPlan.RetrievalRequest(
            memoryType = MemorySemanticType.SOURCE,
            why = "Coverage guard added source evidence for count/temporal recall; typed facts may miss required anchors.",
            query = target.ifBlank { "temporal count recall source evidence" },
            topK = 2,
            preferredClaimPredicates = emptyList(),
            deprioritizedClaimPredicates = emptyList(),
        )
    }

    return copy(
        coverageMode = if (needsCompleteSet || needsTemporalEvidence) {
            MemoryReadPlan.CoverageMode.COMPLETE_SET
        } else {
            coverageMode
        },
        retrievalBudget = guardedBudget,
        retrievalRequests = guardedRequests,
        requireEvidenceFallback = requireEvidenceFallback || needsTemporalEvidence || needsCompleteSet,
    )
}

private fun MemoryReadPlan.withFactualRecallEvidenceRequests(request: MemoryReadRequest): MemoryReadPlan {
    if (!needMemory) return this

    val target = request.targetMessageText()
    if (!target.requiresFactualRecallContextFallback()) return this

    val needsSourceEvidence = target.requiresFactualRecallSourceFallback()
    val guardedBudget = retrievalBudget.copy(
        claims = retrievalBudget.claims.coerceAtLeast(4),
        notes = retrievalBudget.notes.coerceAtLeast(2),
        sources = if (needsSourceEvidence) retrievalBudget.sources.coerceAtLeast(2) else retrievalBudget.sources,
    )
    val query = target.ifBlank { "current factual memory" }
    val guardedRequests = buildList {
        addAll(retrievalRequests)
        if (retrievalRequests.none { it.memoryType == MemorySemanticType.NOTE }) {
            add(
                MemoryReadPlan.RetrievalRequest(
                    memoryType = MemorySemanticType.NOTE,
                    why = "Factual recall guard added note retrieval because exact current context may be summarized outside claims.",
                    query = query,
                    topK = 2,
                    preferredClaimPredicates = emptyList(),
                    deprioritizedClaimPredicates = emptyList(),
                )
            )
        }
        if (needsSourceEvidence && retrievalRequests.none { it.memoryType == MemorySemanticType.SOURCE }) {
            add(
                MemoryReadPlan.RetrievalRequest(
                    memoryType = MemorySemanticType.SOURCE,
                    why = "Factual recall guard added source evidence for current/usual values where claims may be stale or incomplete.",
                    query = query,
                    topK = 2,
                    preferredClaimPredicates = emptyList(),
                    deprioritizedClaimPredicates = emptyList(),
                )
            )
        }
    }

    return copy(
        retrievalBudget = guardedBudget,
        retrievalRequests = guardedRequests,
        requireEvidenceFallback = requireEvidenceFallback || needsSourceEvidence,
    )
}

private fun MemoryReadRequest.memoryThreadContextSummaryForLog(): String {
    val targetIndex = threadContext.messages.indexOfFirst { it.id == threadContext.targetMessageId }
    return "conversation=${threadContext.conversationId.value} thread=${threadContext.threadId.value} " +
        "messages=${threadContext.messages.size} target=${threadContext.targetMessageId.value} targetIndex=$targetIndex"
}

private fun MemoryReadRequest.conversationToolContext(): Map<String, Any?> =
    mapOf(
        "conversationId" to "memory:${threadContext.conversationId.value}",
        "promptCacheKey" to threadContext.conversationId.value,
    )

private fun MemoryReadRequest.targetMessageText(): String {
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

private fun String.requiresCompleteSetCoverage(): Boolean {
    val normalized = lowercase().replace(Regex("\\s+"), " ")
    return normalized.contains("how many") ||
        normalized.contains("how much") ||
        normalized.contains("list all") ||
        normalized.contains("all of") ||
        normalized.contains("every ") ||
        normalized.contains("which one") ||
        normalized.contains("which did") ||
        normalized.contains("which was") ||
        normalized.contains("first") ||
        normalized.contains("second") ||
        normalized.contains("third") ||
        normalized.contains("latest") ||
        normalized.contains("earliest") ||
        normalized.contains("most recent") ||
        normalized.contains("before") ||
        normalized.contains("after")
}

private fun String.requiresTemporalEvidenceFallback(): Boolean {
    val normalized = lowercase().replace(Regex("\\s+"), " ")
    return normalized.contains("how long ago") ||
        Regex("""how many\s+(seconds?|minutes?|hours?|days?|weeks?|months?|years?)\s+ago""").containsMatchIn(normalized) ||
        normalized.contains("when did") ||
        normalized.contains("what date") ||
        normalized.contains("what day") ||
        normalized.contains("which date")
}

private fun String.requiresFactualRecallContextFallback(): Boolean {
    val normalized = lowercase().replace(Regex("\\s+"), " ")
    return normalized.contains("what time") ||
        normalized.contains("which time") ||
        normalized.contains("at what time") ||
        normalized.contains("when do i") ||
        normalized.contains("when did i") ||
        normalized.contains("when is my") ||
        normalized.contains("when are my") ||
        normalized.contains("what is my") ||
        normalized.contains("what are my") ||
        normalized.contains("what do i") ||
        normalized.contains("what did i") ||
        normalized.contains("which do i") ||
        normalized.contains("which did i") ||
        normalized.contains("current") ||
        normalized.contains("currently") ||
        normalized.contains("usual") ||
        normalized.contains("usually") ||
        normalized.contains("typical") ||
        normalized.contains("typically") ||
        normalized.contains("prefer") ||
        normalized.contains("preference") ||
        normalized.contains("constraint") ||
        normalized.contains("routine") ||
        normalized.contains("schedule") ||
        normalized.contains("default")
}

private fun String.requiresFactualRecallSourceFallback(): Boolean {
    val normalized = lowercase().replace(Regex("\\s+"), " ")
    return normalized.contains("what time") ||
        normalized.contains("which time") ||
        normalized.contains("at what time") ||
        normalized.contains("when do i") ||
        normalized.contains("when did i") ||
        normalized.contains("when is my") ||
        normalized.contains("when are my") ||
        normalized.contains("current") ||
        normalized.contains("currently") ||
        normalized.contains("usual") ||
        normalized.contains("usually") ||
        normalized.contains("typical") ||
        normalized.contains("typically") ||
        normalized.contains("prefer") ||
        normalized.contains("preference") ||
        normalized.contains("constraint") ||
        normalized.contains("routine") ||
        normalized.contains("schedule") ||
        normalized.contains("default")
}

private fun String.toAnswerMode(): MemoryReadPlan.AnswerMode =
    when (trim().lowercase().replace("-", "_")) {
        "factual", "fact" -> MemoryReadPlan.AnswerMode.FACTUAL
        "rationale", "reasoning", "why" -> MemoryReadPlan.AnswerMode.RATIONALE
        "action_item", "action_items", "actionitem", "actionitems", "task", "tasks" -> MemoryReadPlan.AnswerMode.ACTION_ITEM
        else -> MemoryReadPlan.AnswerMode.MIXED
    }

private fun String.toCoverageMode(): MemoryReadPlan.CoverageMode =
    when (trim().lowercase().replace("-", "_")) {
        "complete_set", "complete", "full", "exhaustive", "all" -> MemoryReadPlan.CoverageMode.COMPLETE_SET
        else -> MemoryReadPlan.CoverageMode.MINIMAL
    }

private fun String.toCoreBlock(): MemoryReadPlan.CoreBlock? =
    when (trim().lowercase().replace("-", "_").removeSuffix("s")) {
        "profile" -> MemoryReadPlan.CoreBlock.PROFILE
        "action_item", "action_items", "actionitem", "actionitems", "task", "tasks" -> MemoryReadPlan.CoreBlock.ACTION_ITEMS
        "session_summary", "summary" -> MemoryReadPlan.CoreBlock.SESSION_SUMMARY
        else -> null
    }

private fun String.oneLineForReadMemoryLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (oneLine.length <= maxChars) return oneLine
    return oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
