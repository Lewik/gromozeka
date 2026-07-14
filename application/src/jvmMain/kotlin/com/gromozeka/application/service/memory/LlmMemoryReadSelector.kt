package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionResult
import com.gromozeka.domain.model.memory.MemoryReadSelector
import com.gromozeka.domain.model.memory.MemoryReadSelectorTrace
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LlmMemoryReadSelector(
    private val runtime: AiRuntime,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
    batchParallelism: Int = MemoryPipelineParallelism.configuredParallelism(),
) : MemoryReadSelector {
    private val log = KLoggers.logger(this)
    private val batchParallelism = MemoryPipelineParallelism.normalizeParallelism(batchParallelism)

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

        if (request.plan.coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) {
            return selectCompleteSetBatches(request)
        }

        return selectHierarchically(request)
    }

    private suspend fun selectCompleteSetBatches(
        request: MemoryReadSelectionRequest,
    ): MemoryReadSelectionResult {
        val candidateBatches = request.candidateHits.chunked(request.plan.readSelectorCandidateBatchSize())
        log.info {
            "Memory read selector complete-set start: namespace=${request.readRequest.namespace.value} " +
                "candidates=${request.candidateHits.size} batches=${candidateBatches.size} " +
                "batchSize=${request.plan.readSelectorCandidateBatchSize()} parallelism=$batchParallelism"
        }

        val batchResults = selectBatches(
            request = request,
            candidateBatches = candidateBatches,
            passMode = ReadSelectorPassMode.COMPLETE_SET_SELECTION,
            labelPrefix = "complete-set",
        )
        val traceStages = mutableListOf<MemoryReadSelectorTrace.Stage>()
        val decisions = mutableListOf<MemoryReadSelectionResult.Decision>()
        val selectedHits = candidateBatches.flatMapIndexed { index, batch ->
            val batchResult = batchResults[index]
            val safetySurvivors = batch.readSelectorSafetySurvivors(request)
            val llmSelectedRefs = batchResult.selectedHits.mapTo(mutableSetOf()) { it.toReadSelectorItemRef() }
            val safetyAddedSurvivors = safetySurvivors
                .filterNot { it.toReadSelectorItemRef() in llmSelectedRefs }
            val batchSelectedHits = (batchResult.selectedHits + safetyAddedSurvivors)
                .distinctBy { it.toReadSelectorItemRef() }
            val batchSelectedRefs = batchSelectedHits.mapTo(mutableSetOf()) { it.toReadSelectorItemRef() }

            decisions += batchResult.decisions.map { decision ->
                if (decision.ref in batchSelectedRefs && !decision.selected) {
                    decision.copy(
                        selected = true,
                        rank = Int.MAX_VALUE,
                        reason = "Deterministic complete-set safety retained this candidate. ${decision.reason}",
                    )
                } else {
                    decision
                }
            }
            val decidedRefs = batchResult.decisions.mapTo(mutableSetOf()) { it.ref }
            decisions += safetyAddedSurvivors
                .filterNot { it.toReadSelectorItemRef() in decidedRefs }
                .map { hit ->
                    MemoryReadSelectionResult.Decision(
                        ref = hit.toReadSelectorItemRef(),
                        selected = true,
                        rank = Int.MAX_VALUE,
                        reason = "Deterministic complete-set safety retained this candidate.",
                    )
                }
            traceStages += readSelectorTraceStage(
                mode = MemoryReadSelectorTrace.Mode.COMPLETE_SET_SELECTION,
                level = 1,
                batchIndex = index + 1,
                batchCount = candidateBatches.size,
                inputHits = batch,
                llmSelectedHits = batchResult.selectedHits,
                llmCarriedHits = emptyList(),
                safetyAddedHits = safetyAddedSurvivors,
                outputHits = batchSelectedHits,
            )
            batchSelectedHits
        }.distinctBy { it.toReadSelectorItemRef() }

        val selectedRefs = selectedHits.mapTo(mutableSetOf()) { it.toReadSelectorItemRef() }
        val safetyAddedCount = traceStages.sumOf { it.safetyAddedCount }
        val selectedRanks = selectedHits
            .mapIndexed { index, hit -> hit.toReadSelectorItemRef() to index + 1 }
            .toMap()
        val rankedDecisions = decisions
            .distinctBy { it.ref }
            .map { decision ->
                if (decision.ref in selectedRefs) {
                    decision.copy(rank = selectedRanks.getValue(decision.ref))
                } else {
                    decision.copy(rank = Int.MAX_VALUE)
                }
            }

        log.info {
            "Memory read selector complete-set completed: namespace=${request.readRequest.namespace.value} " +
                "candidates=${request.candidateHits.size} batches=${candidateBatches.size} selected=${selectedHits.size}"
        }

        return MemoryReadSelectionResult(
            selectedHits = selectedHits,
            decisions = rankedDecisions,
            summary = buildString {
                append("Complete-set selector evaluated ${request.candidateHits.size} candidates in ${candidateBatches.size} independent batches and selected ${selectedHits.size}.")
                if (safetyAddedCount > 0) append(" Deterministic safety added $safetyAddedCount candidate(s).")
            },
            selectorTrace = MemoryReadSelectorTrace(
                initialCandidateCount = request.candidateHits.size,
                finalCandidateCount = request.candidateHits.size,
                selectedCount = selectedHits.size,
                stages = traceStages,
            ),
        )
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
                    "level=$level candidates=${survivors.size} batches=${candidateBatches.size} " +
                    "batchSize=$candidateBatchSize parallelism=$batchParallelism"
            }

            val batchResults = selectBatches(
                request = request,
                candidateBatches = candidateBatches,
                passMode = ReadSelectorPassMode.INTERMEDIATE_RECALL,
                labelPrefix = "level=$level",
            )
            val nextSurvivors = candidateBatches.flatMapIndexed { index, batch ->
                val batchResult = batchResults[index]
                val selectedSurvivors = batchResult.selectedHits.take(READ_SELECTOR_INTERMEDIATE_LLM_SURVIVORS_PER_BATCH)
                val safetySurvivors = batch.readSelectorSafetySurvivors(request)
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

    private suspend fun selectBatches(
        request: MemoryReadSelectionRequest,
        candidateBatches: List<List<MemoryStore.SearchHit>>,
        passMode: ReadSelectorPassMode,
        labelPrefix: String,
    ): List<MemoryReadSelectionResult> {
        suspend fun select(index: Int, batch: List<MemoryStore.SearchHit>): MemoryReadSelectionResult =
            selectBatch(
                request = request.copy(candidateHits = batch),
                batchLabel = "$labelPrefix batch=${index + 1}/${candidateBatches.size}",
                passMode = passMode,
                runtimeConversationSuffix = if (batchParallelism > 1 && candidateBatches.size > 1) {
                    "read-selector:${passMode.runtimeKey}:b${index + 1}"
                } else {
                    null
                },
            )

        if (batchParallelism == 1 || candidateBatches.size == 1) {
            return candidateBatches.mapIndexed { index, batch -> select(index, batch) }
        }

        return coroutineScope {
            val semaphore = Semaphore(batchParallelism)
            candidateBatches.mapIndexed { index, batch ->
                async {
                    semaphore.withPermit {
                        select(index, batch)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun selectBatch(
        request: MemoryReadSelectionRequest,
        batchLabel: String?,
        passMode: ReadSelectorPassMode,
        runtimeConversationSuffix: String? = null,
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
                "candidates=${request.candidateHits.size} contextMode=${request.plan.contextMode.name} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size} stageMessages=${stageMessages.size}$logSuffix"
        }
        log.info {
            "Memory read selector candidates rendered: namespace=${request.readRequest.namespace.value} " +
                "chars=${renderedCandidates.length}$logSuffix preview=${renderedCandidates.oneLineForReadSelectorLog(8_000)}"
        }

        val hitsByRef = request.candidateHits.associateBy { it.toReadSelectorItemRef() }
        val runtimeConversationKey = "memory:${request.readRequest.threadContext.conversationId.value}" +
            runtimeConversationSuffix?.let { ":$it" }.orEmpty()
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
                        "conversationId" to runtimeConversationKey,
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
        - Candidate memory items are JSON records. Read lifecycle_state, selection_hint, predicate_semantic_kinds, supports, supersedes, and overridden_by before choosing.
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
        ${MemoryReadPromptPolicy.selectorCountAndCategoryRules()}
        - For specifically qualified questions, keep sources or candidates needed to satisfy every explicit qualifier in the question. Do not select only a simpler direct claim when it conflicts with a required qualifier such as ingredient, place, date, participant, role, job title, position, source, color, amount, or relation.
        - A shared anchor can connect candidates only when it explicitly identifies the same fully-qualified target. Do not treat related work, roles, job titles, positions, artifacts, projects, events, routes, or relationships as interchangeable merely because they share a topic, venue, person, or generic label. A different job role, role title, or position title is a different qualified target unless retrieved memory explicitly says the titles refer to the same role.
        - Keep anchor-level metadata candidates when they can supply a missing requested detail for an already matched target: for example a venue, publisher, conference, program, service, route, or event date, deadline, schedule, price, policy, or threshold. This is allowed only when another candidate explicitly links the asked target to the same named anchor and the metadata is not tied to a different named target. Reject the bridge when retrieved memory has competing anchors or competing values for the same target.
        - For multi-hop temporal questions, keep complementary facts together: event dates, question dates, relative offsets, lead times, durations, and sources with exact timing wording. Do not select only the candidate that contains the last lexical hop.
        - For derived answers, a candidate that supplies a missing operand is relevant even when it lacks the other operands. Keep complementary operand candidates together; do not reject one solely because another selected candidate is needed to use it.
        - For relative-duration questions that name an anchor event, such as "how many days/weeks/months ago did X when/at the time Y", keep the dated candidate for X and the dated anchor event Y. Do not default to the current/question date when the wording asks for the interval as of another remembered event.
        - For questions asking how long the user had been doing an activity when an anchor event happened, keep explicit start/begin/first-participation evidence for that activity even when an as-of duration or tenure candidate also exists. Do not reject the start event as less direct; it can be the required duration-to-anchor operand.
        - When one candidate gives a relative time anchored to another event, select the anchor event timing candidate too. A lead time or offset alone is not sufficient for "when" or "how long ago" questions.
        - When a candidate says something was booked, reserved, prepared, started, finished, or decided N days/weeks/months in advance, before, or after another remembered event, that candidate supplies an offset, not elapsed time from the current/question date. For "how long ago" or "when" answers, keep the remembered event timing and source/question-date anchor needed to convert that offset.
        - For relative-time arithmetic, a selected set is insufficient unless it includes every explicit anchor needed to compute the answer. Rejecting an event-date or question-date anchor as "not needed" is incorrect when another selected candidate only gives a lead time, offset, or duration.
        - For derived duration answers, keep every operand needed for the calculation even when an operand is not itself the final answer. Examples: current role tenure may require both total company tenure and time before promotion to the current role; membership duration may require both join date and target date; recurring activity totals may require each contributing count.
        - For formal education timeline or duration questions from one education stage to completion of another, keep intermediate formal education credentials, institutions, enrollment, transfer, and completion milestones. Endpoints or direct degree-duration claims alone may undercount the elapsed formal education path.
        - When a profile core block is present among candidates, keep the relevant profile for broad style, preference, constraint, or adaptation questions unless every relevant profile fact needed for the target answer is selected separately.
        - A single specific style claim is not sufficient for a broad adaptation question when the relevant profile also contains language, tone, formatting, or answer-detail preferences.
        - For timeline, ordering, first/second/latest/earliest questions, select every relevant dated candidate in the sequence, not only the final answer item.
        - For latest/most-recent questions about when the user started, began, first used, acquired, or got access to something, keep actual lifecycle/use/acquisition candidates separate from later recommendation, preference, choice, or access-path decisions. A later choice is not a later started-using event unless memory explicitly says the user actually started, used, acquired, or got access to that option.
        - For named-date or relative-date questions, compare the target date with candidate event dates and source/session dates. When the question asks which event the user experienced, candidate event dates and local event wording are stronger than a matching source/session date. Keep a source/session-date match only when the candidate text describes the same target event or no event-dated candidate matches.
        - For date-scoped questions, never choose an explicit out-of-period event as the direct answer when any in-period claim, source, note, or other candidate matches the requested event kind and can contain the missing detail. The out-of-period candidate may be conflict/supporting context, but it must not replace the in-period candidate solely because it has a more literal participant, venue, or action phrase.
        - For ambiguous date-scoped wording such as "the music event last Saturday", resolve the date/window from the question before choosing the event identity. Do not let an out-of-period candidate define the target event; a same-broad-category in-period event is the target candidate even when an older event has stronger lexical overlap.
        - For completed-experience questions, evidence that the user actually did, attended, took, used, received, or completed something is stronger than same-date planning, booking, reservation, recommendation, or generated artifact evidence. A booking or reservation date is not the event date unless memory explicitly says the booked event happened on that date.
        - For relative month-count questions such as "two months ago", derive the approximate calendar-offset target from the current/question date and prefer candidates closest to that offset. Do not treat any earlier recent event as matching the requested offset; for example, one month ago is not two months ago when another otherwise relevant candidate is near the two-month target.
        - For "past weekend", "last week", "yesterday", or other relative-window questions, derive the target interval from the current/question date. An event with its own local cue such as "today" resolves to the source/session date and is outside the window when that date is outside the target interval.
        - Do not smear one relative date cue across unrelated events in the same source. A source can mention "this weekend" for one event and "today" for another; resolve each event from its own local wording and the source/session date.
        - First-person recency wording such as "still recovering from", "just got back from", "previous", "recent", or "last" can make a completed event relevant to the source/session date. Prefer that completed event over a plan or booking created in the same source when the question asks what the user experienced.
        - Missing date evidence is uncertainty, not contradiction. Keep otherwise matching candidates or sources with no explicit date so the answer can compare them; treat candidates with an explicit different date as lower-priority conflict or exclusion evidence unless the question asks for that period.
        - For named-date or relative-window questions with broad or noisy action wording, target-period relevance can beat literal verb matching. Keep target-period lifecycle variants such as plans, checklists, appointments, requests, estimates, recommendations, setup discussions, or other object-matching lifecycle evidence before outside-period completed actions only when the requested action/status is not itself a required qualifier. Preserve lifecycle state instead of treating plans, bookings, or recommendations as completed user-experienced events.
        - For place-visit questions, treat user-attended venue events such as lectures, guided tours, exhibits, appointments, or behind-the-scenes tours at that venue as visit evidence when the venue and target time match. Prefer the venue/time match over a more literal "visited" or "guided tour" event from a different time period.
        - Do not infer that an unrelated explicit date satisfies a named date such as Valentine's Day, birthday, holiday, or anniversary. If date evidence conflicts, select enough candidates to expose the conflict.
        - When Planned context mode is FACTUAL or ACTION_ITEM and Require evidence fallback is false, prefer active typed memory over source candidates.
        - When candidates disagree about a current/usual value, schedule, time, preference, or constraint, select enough candidates to expose the conflict and prefer the newer relevant active typed memory or its supporting source evidence.
        - Raw sources are evidence, not current truth. Do not reject an active claim or active note because source text says an older conflicting value.
        - Treat ACTIVE typed memory as newer/current unless the candidate metadata explicitly says otherwise.
        - For current-state answers, never use stale, superseded, retracted, expired, or candidate memory as stronger evidence than any relevant ACTIVE memory.
        - For current-state answers at a later target date, keep later dated plans, intentions, or scheduled changes that may have become the current state by that date. Select them together with any older current-state candidate so the answer can reason about whether the old state is stale.
        - For questions about an initial, original, previous, older, or before/after state, non-current typed memory can be the direct historical answer. A direct SUPERSEDED, RETRACTED, EXPIRED, or otherwise non-current typed claim for the same semantic slot is higher priority than the active replacement for the historical value being asked. Select both the older candidate and the current replacement when the answer depends on comparing old vs current state; do not reject the older candidate merely because the active replacement answers the current value.
        - For target questions about a current named metric, record, score, benchmark, quota, threshold, or personal best, prefer a direct current metric/record typed claim over a contextual historical event or goal claim. Historical events answer when the event happened; current metric claims answer what the remembered value is.
        - Do not reject a direct current metric/record claim merely because its normalized text uses a broader metric label than the target wording when its context, scope, or evidence binds it to the same named domain.
        - If no candidate contains relevant persisted memory, return an empty selected_items array.

        Planned context mode: ${request.plan.contextMode.name}
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
    val runtimeKey: String,
    val promptLabel: String,
    val goalText: String,
    val selectionRule: String,
) {
    INTERMEDIATE_RECALL(
        runtimeKey = "intermediate",
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
        runtimeKey = "final",
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
    },
    COMPLETE_SET_SELECTION(
        runtimeKey = "complete-set",
        promptLabel = "complete_set_selection",
        goalText = "Select every distinct candidate in this batch that is relevant to the complete requested set.",
        selectionRule = "Select the complete relevant set in this batch; do not reduce it to a representative sample.",
    ) {
        override fun extraRules(plan: MemoryReadPlan): String = """
        - This batch is an independent final partition of a complete-set query. Its selected items will be unioned with selected items from the other batches without another LLM reduction pass.
        - Decide each candidate on its own relevance to the requested set. Cross-batch ranking and representative sampling are unnecessary.
        - Reject candidates that do not belong to the requested set; keep every distinct candidate that does.
        """.trimIndent()
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
            is Conversation.Message.ContentItem.ContextCompactionResult -> item.readSelectorText()
            is Conversation.Message.ContentItem.UnknownJson -> item.json.toString()
            is Conversation.Message.ContentItem.Thinking -> null
        }
    }.joinToString("\n").trim()
}

private fun Conversation.Message.ContentItem.ContextCompactionResult.readSelectorText(): String =
    when (val payload = payload) {
        is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary -> payload.text
        is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
            "[context_compaction:${providerScope?.provider ?: "unknown"}]"
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

private fun List<MemoryStore.SearchHit>.readSelectorSafetySurvivors(
    request: MemoryReadSelectionRequest,
): List<MemoryStore.SearchHit> {
    val plan = request.plan
    val scoreLeaders = sortedByReadSelectorStrength(plan).take(plan.readSelectorScoreSafetyLimit())
    val preferredPredicateLeaders = filter { it.matchesPreferredClaimPredicate(plan) }
        .sortedByReadSelectorStrength(plan)
        .take(plan.readSelectorPreferredPredicateSafetyLimit())
    val temporalLeaders = temporalSafetySurvivors(request)
    val activeTypedLeaders = filter { it.isActiveTypedMemory() }
        .sortedByReadSelectorStrength(plan)
        .take(plan.readSelectorActiveTypedSafetyLimit())
    val profileLeaders = if (MemoryReadPlan.CoreBlock.PROFILE in plan.coreBlocks) {
        filterIsInstance<MemoryStore.SearchHit.ProfileHit>()
            .sortedByReadSelectorStrength(plan)
            .take(1)
    } else {
        emptyList()
    }
    val evidenceLeaders = if (plan.readSelectorEvidenceSafetyLimit() > 0) {
        filterIsInstance<MemoryStore.SearchHit.SourceHit>()
            .sortedByReadSelectorStrength(plan)
            .take(plan.readSelectorEvidenceSafetyLimit())
    } else {
        emptyList()
    }
    val evidenceSupportedTypedLeaders = evidenceSupportedTypedSafetySurvivors(plan)
    val modeLeaders = when (plan.contextMode) {
        MemoryReadPlan.ContextMode.RATIONALE -> filterIsInstance<MemoryStore.SearchHit.NoteHit>() +
            filterIsInstance<MemoryStore.SearchHit.EpisodeHit>()
        MemoryReadPlan.ContextMode.ACTION_ITEM -> filterIsInstance<MemoryStore.SearchHit.ActionItemHit>()
        else -> emptyList()
    }
        .sortedByReadSelectorStrength(plan)
        .take(1)

    return (
        scoreLeaders +
            preferredPredicateLeaders +
            temporalLeaders +
            evidenceSupportedTypedLeaders +
            activeTypedLeaders +
            profileLeaders +
            evidenceLeaders +
            modeLeaders
        )
        .distinctBy { it.toReadSelectorItemRef() }
        .take(request.readSelectorSafetySurvivorLimit())
}

private fun MemoryReadSelectionRequest.finalSelectionSafetyAddedHits(
    selectedHits: List<MemoryStore.SearchHit>,
): List<MemoryStore.SearchHit> {
    val selectedRefs = selectedHits.mapTo(mutableSetOf()) { it.toReadSelectorItemRef() }

    if (plan.coverageMode != MemoryReadPlan.CoverageMode.COMPLETE_SET) {
        return temporalSafetySurvivors()
            .filterNot { it.toReadSelectorItemRef() in selectedRefs }
            .take(READ_SELECTOR_TEMPORAL_FINAL_SAFETY_ADD_LIMIT)
    }

    return candidateHits
        .readSelectorSafetySurvivors(this)
        .filterNot { it.toReadSelectorItemRef() in selectedRefs }
}

private fun MemoryReadSelectionRequest.temporalSafetySurvivors(): List<MemoryStore.SearchHit> =
    candidateHits.temporalSafetySurvivors(this)

private fun String.withFinalSafetySummary(safetyAddedHits: List<MemoryStore.SearchHit>): String =
    if (safetyAddedHits.isEmpty()) {
        this
    } else {
        "$this Final safety added ${safetyAddedHits.size} complete-set survivor(s)."
    }

private fun MemoryReadSelectionRequest.readSelectorSafetySurvivorLimit(): Int =
    if (plan.coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) {
        READ_SELECTOR_COMPLETE_SET_SAFETY_SURVIVORS_PER_BATCH
    } else if (isTemporalSelectorQuery()) {
        READ_SELECTOR_TEMPORAL_SAFETY_SURVIVORS_PER_BATCH
    } else {
        READ_SELECTOR_SAFETY_SURVIVORS_PER_BATCH
    }

private fun MemoryReadPlan.readSelectorScoreSafetyLimit(): Int =
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) 4 else READ_SELECTOR_SCORE_SAFETY_PER_BATCH

private fun MemoryReadPlan.readSelectorActiveTypedSafetyLimit(): Int =
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) 12 else READ_SELECTOR_ACTIVE_TYPED_SAFETY_PER_BATCH

private fun MemoryReadPlan.readSelectorPreferredPredicateSafetyLimit(): Int =
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) 8 else 2

private fun MemoryReadPlan.readSelectorEvidenceSafetyLimit(): Int =
    when {
        coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET -> (retrievalBudget.sources.takeIf { it > 0 } ?: 3)
        requireEvidenceFallback -> 1
        else -> 0
    }

private fun MemoryReadPlan.readSelectorHardFinalSurvivorLimit(): Int =
    READ_SELECTOR_HARD_FINAL_SURVIVOR_LIMIT

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
    READ_SELECTOR_INTERMEDIATE_LLM_SURVIVORS_PER_BATCH

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

private fun List<MemoryStore.SearchHit>.evidenceSupportedTypedSafetySurvivors(
    plan: MemoryReadPlan,
): List<MemoryStore.SearchHit> {
    if (plan.coverageMode != MemoryReadPlan.CoverageMode.COMPLETE_SET) return emptyList()

    val sourceIds = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
        .mapTo(mutableSetOf()) { it.source.id }
    if (sourceIds.isEmpty()) return emptyList()

    return filter { hit ->
        hit.isActiveTypedMemory() && hit.readSelectorEvidenceSourceIds().any { it in sourceIds }
    }
        .sortedByReadSelectorStrength(plan)
        .take(READ_SELECTOR_COMPLETE_SET_EVIDENCE_TYPED_SAFETY_PER_BATCH)
}

private fun List<MemoryStore.SearchHit>.temporalSafetySurvivors(
    request: MemoryReadSelectionRequest,
): List<MemoryStore.SearchHit> {
    if (!request.isTemporalSelectorQuery()) return emptyList()

    val dateTokens = request.selectorQueryDateTokens()
    if (dateTokens.isEmpty()) return emptyList()

    val temporalClaims = filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
        .filter { it.isActiveTemporalClaim() }
        .filter { it.claim.matchesAnySelectorDateToken(dateTokens) }
        .sortedByReadSelectorStrength(request.plan)
        .take(READ_SELECTOR_TEMPORAL_CLAIM_SAFETY_PER_BATCH)
    val temporalSources = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
        .filter { it.source.matchesAnySelectorDateToken(dateTokens) }
        .sortedByReadSelectorStrength(request.plan)
        .take(READ_SELECTOR_TEMPORAL_SOURCE_SAFETY_PER_BATCH)

    return (temporalClaims + temporalSources).distinctBy { it.toReadSelectorItemRef() }
}

private fun MemoryReadSelectionRequest.isTemporalSelectorQuery(): Boolean =
    sourceCandidateQuery().let { query ->
        selectorTemporalCueRegex.containsMatchIn(query) || selectorDateRegex.containsMatchIn(query)
    }

private fun MemoryReadSelectionRequest.selectorQueryDateTokens(): Set<String> =
    selectorDateRegex.findAll(sourceCandidateQuery())
        .flatMap { match ->
            val year = match.groupValues[1]
            val month = match.groupValues[2].padStart(2, '0')
            val day = match.groupValues[3].padStart(2, '0')
            sequenceOf("$year-$month-$day", "$year/$month/$day")
        }
        .toSet()

private fun MemoryStore.SearchHit.ClaimHit.isActiveTemporalClaim(): Boolean =
    claim.status == MemoryClaim.Status.ACTIVE &&
        claim.archivedAt == null &&
        (
            claim.predicatePolicy?.temporalPolicy == MemoryPredicateDefinition.TemporalPolicy.TIME_SCOPED ||
                MemoryPredicateDefinition.SemanticKind.EVENT_PARTICIPATION in claim.predicatePolicy?.semanticKinds.orEmpty()
            )

private fun MemoryClaim.matchesAnySelectorDateToken(dateTokens: Set<String>): Boolean =
    sequenceOf(
        validFrom?.toString(),
        validTo?.toString(),
        normalizedText,
        contextText,
    )
        .filterNotNull()
        .plus(evidenceRefs.asSequence().mapNotNull { it.cachedQuote })
        .any { text -> dateTokens.any { it in text } }

private fun MemorySource.matchesAnySelectorDateToken(dateTokens: Set<String>): Boolean =
    sequenceOf(contentText, searchText)
        .filterNotNull()
        .any { text -> dateTokens.any { it in text } }

private fun List<MemoryStore.SearchHit>.sortedByReadSelectorStrength(
    plan: MemoryReadPlan,
): List<MemoryStore.SearchHit> =
    sortedWith(
        compareByDescending<MemoryStore.SearchHit> { it.readSelectorPredicatePriority(plan) }
            .thenByDescending { it.score }
            .thenByDescending { it.readSelectorImportance() }
            .thenBy { it.toReadSelectorItemRef().type.name }
            .thenBy { it.contentStableSortKey() }
            .thenBy { it.toReadSelectorItemRef().id }
    )

private fun MemoryStore.SearchHit.matchesPreferredClaimPredicate(plan: MemoryReadPlan): Boolean =
    readSelectorPredicatePriority(plan) > 0

private fun MemoryStore.SearchHit.readSelectorPredicatePriority(plan: MemoryReadPlan): Int {
    if (this !is MemoryStore.SearchHit.ClaimHit) return 0

    val predicates = buildSet {
        add(claim.predicate.lowercase())
        claim.predicateFamily?.lowercase()?.let(::add)
    }
    val preferred = plan.retrievalRequests
        .filter { it.memoryType == MemorySemanticType.CLAIM }
        .flatMap { it.preferredClaimPredicates }
        .mapTo(mutableSetOf()) { it.lowercase() }
    val deprioritized = plan.retrievalRequests
        .filter { it.memoryType == MemorySemanticType.CLAIM }
        .flatMap { it.deprioritizedClaimPredicates }
        .mapTo(mutableSetOf()) { it.lowercase() }

    return when {
        predicates.any { it in preferred } -> 2
        predicates.any { it in deprioritized } -> -1
        else -> 0
    }
}

private fun MemoryStore.SearchHit.readSelectorEvidenceSourceIds(): List<MemorySource.Id> =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> claim.evidenceRefs.map { it.sourceId }
        is MemoryStore.SearchHit.NoteHit -> note.evidenceRefs.map { it.sourceId }
        is MemoryStore.SearchHit.ActionItemHit -> actionItem.evidenceRefs.map { it.sourceId }
        is MemoryStore.SearchHit.EpisodeHit -> episode.evidenceRefs.map { it.sourceId }
        is MemoryStore.SearchHit.ProfileHit,
        is MemoryStore.SearchHit.EntityHit,
        is MemoryStore.SearchHit.SourceHit,
        is MemoryStore.SearchHit.RunHit,
        -> emptyList()
    }

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
private const val READ_SELECTOR_SAFETY_SURVIVORS_PER_BATCH = 4
private const val READ_SELECTOR_COMPLETE_SET_SAFETY_SURVIVORS_PER_BATCH = 16
private const val READ_SELECTOR_COMPLETE_SET_EVIDENCE_TYPED_SAFETY_PER_BATCH = 8
private const val READ_SELECTOR_TEMPORAL_CLAIM_SAFETY_PER_BATCH = 6
private const val READ_SELECTOR_TEMPORAL_SOURCE_SAFETY_PER_BATCH = 2
private const val READ_SELECTOR_TEMPORAL_FINAL_SAFETY_ADD_LIMIT = 4
private const val READ_SELECTOR_SCORE_SAFETY_PER_BATCH = 2
private const val READ_SELECTOR_ACTIVE_TYPED_SAFETY_PER_BATCH = 2
private const val READ_SELECTOR_TEMPORAL_SAFETY_SURVIVORS_PER_BATCH = 8
private const val READ_SELECTOR_HARD_FINAL_SURVIVOR_LIMIT = 20
private const val READ_SELECTOR_TRACE_REF_LIMIT = 24

private val selectorDateRegex = Regex("""\b(20\d{2})[-/](\d{1,2})[-/](\d{1,2})\b""")
private val selectorTemporalCueRegex = Regex(
    """\b(?:today|tonight|yesterday|tomorrow|last|next|this|past|weekend|week|month|year|morning|afternoon|evening|recently|ago|before|after|earliest|latest|most recent|first|second|third|order)\b""",
    RegexOption.IGNORE_CASE,
)
