package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySourceUsagePolicy
import com.gromozeka.domain.model.memory.MemoryWriteRouter
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LlmMemoryWriteRouter(
    private val runtime: AiRuntime,
    private val timezone: String,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryWriteRouter {
    private val log = KLoggers.logger(this)

    override suspend fun route(request: DirectStructuredMemoryWriteRequest): MemoryRouteDecision {
        if (request.source.contentText.isBlank()) {
            return MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.NOOP,
                reason = "Source text is blank",
            )
        }

        val stageMessages = request.toMemoryStageMessages(
            stageName = "write-router",
            taskPrompt = buildRouterUserPrompt(request),
        )

        log.info {
            "Memory router LLM call: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "threadContext=${request.memoryThreadContextSummaryForLog()} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size} stageMessages=${stageMessages.size}"
        }

        val result = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.WRITE_ROUTER_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.WriteRouter,
                    toolContext = mapOf(
                        "memoryRouter" to true,
                        "memoryNamespace" to request.namespace.value,
                        "memorySourceId" to request.source.id.value,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "write-router",
            logContext = "namespace=${request.namespace.value} source=${request.source.id.value}",
            parse = { json.decodeFromString<MemoryRouterResponse>(it).toRouteDecision() },
        )

        log.info {
            "Memory router raw response: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "chars=${result.rawText.length} response=${result.rawText.oneLineForRouterMemoryLog(4_000)}"
        }

        return result.value
    }

    private fun buildRouterUserPrompt(request: DirectStructuredMemoryWriteRequest): String = """
        Memory stage: MemoryRouter v3.
        Current time: ${Clock.System.now()}
        Timezone: $timezone
        Namespace: ${request.namespace.value}

        Stage instructions:
        $MEMORY_ROUTER_SYSTEM_PROMPT

        TARGET_MESSAGE source data:
        ${request.source.renderLatestTurn()}
    """.trimIndent()

    @Serializable
    private data class MemoryRouterResponse(
        val decision: String,
        @SerialName("memory_types")
        val memoryTypes: List<String> = emptyList(),
        val salience: Double = 0.0,
        @SerialName("source_policy")
        val sourcePolicy: SourcePolicy = SourcePolicy(),
        @SerialName("source_search_text")
        val sourceSearchText: String = "",
        val reason: String = "",
    ) {
        fun toRouteDecision(): MemoryRouteDecision =
            MemoryRouteDecision(
                decision = decision.toDecision(),
                memoryTypes = memoryTypes.mapNotNull { it.toMemorySemanticType() }.toSet(),
                salience = salience.coerceIn(0.0, 1.0),
                sourcePolicy = sourcePolicy.toUsagePolicy(),
                sourceSearchText = sourceSearchText.trim().take(4_000).ifBlank { null },
                reason = reason,
            )
    }

    @Serializable
    private data class SourcePolicy(
        @SerialName("allow_structured_extraction")
        val allowStructuredExtraction: Boolean = true,
        @SerialName("allow_recall")
        val allowRecall: Boolean = true,
        @SerialName("allow_evidence_hydration")
        val allowEvidenceHydration: Boolean = true,
        val reason: String = "standard",
    ) {
        fun toUsagePolicy(): MemorySourceUsagePolicy =
            MemorySourceUsagePolicy(
                allowStructuredExtraction = allowStructuredExtraction,
                allowRecall = allowRecall,
                allowEvidenceHydration = allowEvidenceHydration,
                reason = reason.trim().ifBlank { "standard" },
            )
    }

    private companion object {
        val MEMORY_ROUTER_SYSTEM_PROMPT = """
            You are MemoryRouter v3 for a long-term AI agent.

            Your job is to decide whether the latest turns contain durable information worth storing in long-term memory, and if so, which write mode to use.

            Return JSON:
            {
              "decision": "noop | direct_structured_write | note_write | mixed | forget_request",
              "memory_types": ["claim", "note", "action_item", "profile", "source"],
              "salience": 0.0,
              "source_policy": {
                "allow_structured_extraction": true,
                "allow_recall": true,
                "allow_evidence_hydration": true,
                "reason": "short source usage explanation"
              },
              "source_search_text": "English search-only paraphrase of TARGET_MESSAGE, or empty string",
              "reason": "short explanation"
            }

            Core definitions:
            - Current-turn execution command: an instruction for the assistant to act now in this chat/codebase, such as edit this, clean this up, run tests, commit and push, do the second wave, or finish it. It is audit-only by default.
            - Internal action item memory: a follow-up/todo/commitment that must be remembered after this turn, or an explicit lifecycle update to an existing stored action item, such as keep an open action item, close the follow-up, cancel the todo, unblock the action item, or mark it done.
            - External work item: a Jira Story, GitHub Issue, ticket, backlog item, issue-tracker task, assignee/status row, or project-management record. Do not route it as action_item merely because it has an assignee, status, priority, or "Type=Story"; route durable information about it as note/claim/source unless TARGET_MESSAGE explicitly asks Gromozeka to remember a follow-up.
            - Durable claim/rule: a stable reusable fact, preference, prior experience, dated personal event, constraint, workflow rule, project state, or user requirement. It is not merely a one-time instruction to execute now.
            - Note memory: reusable rationale, trade-offs, design direction, lesson, procedure, or document digest. It is not a transcript summary of the current instruction.
            - Document ingest source: TARGET_MESSAGE is imported or pasted document content when source metadata contains source_kind=document, origin=provided_document_section, origin=pasted_document_section, or the source text starts with Document source/section metadata. Treat the document as imported evidence, not as a chat command; stable document facts may become imported claims with document scope.
            - Forced memory write: source metadata force_memory_write=true means the user/tool explicitly requested memory ingestion for this exact target. Do not return "noop"; choose the best non-noop write mode for the content.

            Decision policy:
            - "noop" for greetings, filler, repetition, low-value chatter, and transient content.
            - "direct_structured_write" for explicit durable facts, stable preferences, prior experiences, dated personal events, reusable rules, clear durable status changes, deadlines, commitments, and durable action item lifecycle commands.
            - "note_write" for rationale, trade-offs, design direction, evolving plans, local conclusions, lessons, and document digests when there are no distinct structured facts/preferences/events worth extracting.
            - "mixed" when the material contains both structured facts/action items/preferences/dated events and richer rationale/context.
            - "forget_request" when TARGET_MESSAGE explicitly asks to forget, remove, delete, or stop remembering previously stored information.
            - For document ingest sources, prefer "note_write" for conceptual/rationale/procedural sections and "mixed" when the document section also states stable facts, rules, or action item lifecycle data. Do not reject the source merely because it is not a user utterance.
            - For forced ingestion of past conversations, do not collapse extractable user preferences, prior experiences, recommendation patterns, or dated events into note-only memory. Use "mixed" when a transcript contains both a reusable note and claim-like signals.
            - Include memory_type "action_item" only when TARGET_MESSAGE explicitly creates, repeats, updates, closes, cancels, blocks, unblocks, reprioritizes, or assigns a durable follow-up/action item/todo for Gromozeka memory.
            - Do not include memory_type "action_item" for an external Jira Story, GitHub Issue, ticket, backlog item, or project-management record unless TARGET_MESSAGE explicitly asks Gromozeka to remember or track a follow-up about it.
            - Do not include memory_type "action_item" for a normal implementation command unless the target explicitly says it should be tracked after this turn.
            - Do not include memory_type "claim" for an action item lifecycle command unless TARGET_MESSAGE independently asserts a stable reusable fact/rule.
            - Do not include memory_type "note" merely to summarize a one-off command.
            - Include memory_type "source" for "forget_request".
            - Attributed viewpoints such as "Alice thinks X" or "Bob says Y" are claim-worthy because the durable fact is the attribution, even when X and Y conflict.
            - A single weak uncertain observation without rationale, decision, plan, lesson, or reusable analysis should usually be "noop", not "note_write" or "direct_structured_write".
            - A current-turn execution command such as "edit it", "clean it up", "run tests", "commit and push", "do the second wave", or "finish it" is usually "noop" with audit-only source policy unless it explicitly changes a tracked action item or states a durable rule/fact.
            - If source metadata force_memory_write=true and content does not fit claims/action_items/profile, return "note_write" with memory_types=["note","source"] and standard source policy.

            Source policy:
            - Default source_policy allows structured extraction, recall, and evidence hydration for non-noop memory-worthy sources.
            - For low-value "noop" greetings, filler, pure repetition, and no-content chatter, keep the source only as audit material:
              allow_structured_extraction=false, allow_recall=false, allow_evidence_hydration=false.
            - For current-turn execution commands with no durable assertion and no stored action item lifecycle update, keep the source only as audit material:
              allow_structured_extraction=false, allow_recall=false, allow_evidence_hydration=false.
            - For "noop" user statements that are not durable enough for structured memory but still contain a concrete name, source wording, uncertainty, attribution, or a soft observation the user may later ask about, keep them as recallable source-only memory:
              allow_structured_extraction=false, allow_recall=true, allow_evidence_hydration=true.
            - If the target explicitly says the content should not be remembered, stored, or treated as a fact/preference/project fact, keep the source only as audit material:
              allow_structured_extraction=false, allow_recall=false, allow_evidence_hydration=false.
            - For "forget_request", keep the forget command as audit material only:
              allow_structured_extraction=false, allow_recall=false, allow_evidence_hydration=false.
            - Questions, probes, and "I remember X, is that still true?" turns are not evidence of X. If they contain no new durable assertion, route "noop" and make them audit-only.
            - Recall/probe questions about existing memory are "noop" even if they include answer-shaping instructions such as "keep it short", "answer briefly", or "answer with only the value".
            - Examples of audit-only noop targets: "How should you adapt your answer style for me?", "What do you remember about my preferences?", "Which open follow-up exists?", "What database does this project use?".
            - A question becomes write-worthy only when TARGET_MESSAGE also asserts new durable content or explicitly asks to remember/update/forget a memory.
            - If the target is a quoted diagnostic/repeat-only instruction and says not to remember it, also set all three source policy gates to false.
            - If the target is a normal correction, disagreement, retraction, or clarification, do not block source usage; route the correction as durable memory when appropriate.
            - For document ingest sources, keep source_policy usable for structured extraction, recall, and evidence hydration unless the source itself explicitly says it must not be remembered.

            Source search bridge:
            - Always return source_search_text.
            - source_search_text is derived search/index text only, never evidence.
            - Use concise English even when TARGET_MESSAGE is in another language.
            - Preserve important names, product names, dates, identifiers, and exact entities.
            - Include uncertainty, negation, correction, and attribution markers when present.
            - For "forget_request", make source_search_text the thing to forget, not the instruction itself. Example: "forget that I prefer Toyota cars" -> "user preference Toyota cars".
            - For low-value greetings or blocked do-not-remember sources, use an empty string.
            - For "noop" questions/probes, source_search_text may describe the question for audit/debug, but the source must not be recallable evidence.
            - Do not invent facts beyond TARGET_MESSAGE; paraphrase only what the source actually says.

            Examples:
            - "Сотредактируй, я потом посмотрю diff в IDE" -> decision="noop", memory_types=[], audit-only source policy.
            - "Дочисти оставшиеся ссылки прямо сейчас" -> decision="noop", memory_types=[], audit-only source policy.
            - "Закрой три таски по memory cleanup" -> decision="direct_structured_write", memory_types=["action_item"], no claim/note/profile unless a stable reusable fact is also asserted.
            - "Memory MVP follow-up: keep an open action item to review command-vs-memory routing" -> decision="direct_structured_write", memory_types=["action_item"].
            - Past transcript: "I need meal prep ideas with quinoa and roasted vegetables... I've had grilled chicken and turkey breast before, but I've never tried lentil bolognese" -> decision="mixed", memory_types=["claim","note","source"].
            - Past transcript: "I just got back from a networking event that ran from 6 PM to 8 PM today" -> decision="direct_structured_write" or "mixed", memory_types=["claim","source"].
            - "Jira Story MV-344, Type=Story, Assignee=Lev, Status=In Progress" -> decision="note_write" or "mixed", memory_types=["note","source"] plus "claim" only for durable facts; no action_item unless the target explicitly asks Gromozeka to track a follow-up.
            - "For this project, normally edit only gromozeko.dev and update gromozeko.beta by pulling repository changes into beta" -> decision="direct_structured_write", memory_types=["claim"].
            - "We chose retrieval-before-update because pronouns and corrections need existing memory context" -> decision="note_write" or "mixed", memory_types=["note"] plus "claim" only if there is a distinct durable fact.

            Hard rules:
            - Prefer "noop" over memory pollution.
            - Do not treat every question as an action item.
            - Do not force rationale into claims.
            - Do not force weak one-off observations into notes.
            - Do not use MEMORY-ONLY CONTROL/INSTRUCTION messages as semantic evidence or as the reason for routing decisions.
            - If the user explicitly asks to remember something, do not return "noop", unless the same target says not to remember/store/treat that content as memory.
            - Return valid JSON only.
        """.trimIndent()
    }
}

private fun String.toDecision(): MemoryRouteDecision.Decision =
    when (trim().lowercase().replace("-", "_")) {
        "noop" -> MemoryRouteDecision.Decision.NOOP
        "direct_structured_write" -> MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE
        "note_write" -> MemoryRouteDecision.Decision.NOTE_WRITE
        "mixed" -> MemoryRouteDecision.Decision.MIXED
        "forget_request" -> MemoryRouteDecision.Decision.FORGET_REQUEST
        else -> throw IllegalArgumentException("Unknown memory router decision: $this")
    }

internal fun String.toMemorySemanticType(): MemorySemanticType? =
    when (trim().lowercase().removeSuffix("s")) {
        "claim", "fact", "preference", "commitment" -> MemorySemanticType.CLAIM
        "note", "rationale", "context", "lesson", "decision" -> MemorySemanticType.NOTE
        "action_item", "action_items", "actionitem", "actionitems", "task", "tasks", "deadline" -> MemorySemanticType.ACTION_ITEM
        "profile" -> MemorySemanticType.PROFILE
        "source", "evidence" -> MemorySemanticType.SOURCE
        "entity" -> MemorySemanticType.ENTITY
        "episode", "experience" -> MemorySemanticType.EPISODE
        else -> null
    }

private fun String.oneLineForRouterMemoryLog(maxChars: Int): String {
    val normalized = replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= maxChars) {
        return normalized
    }
    return normalized.take(maxChars) + "...[truncated ${normalized.length - maxChars} chars]"
}
