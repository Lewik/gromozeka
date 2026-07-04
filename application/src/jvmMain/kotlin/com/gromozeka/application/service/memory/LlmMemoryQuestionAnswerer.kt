package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryReadTrace
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal class LlmMemoryQuestionAnswerer(
    private val runtime: AiRuntime,
    private val runtimeSystemPrompts: List<String>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun answer(
        question: String,
        readResult: MemoryReadResult,
        conversationId: Conversation.Id,
    ): MemoryQuestionAnswerResult {
        val response = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = listOf(
                    Conversation.Message(
                        id = Conversation.Message.Id("memory-read-answer:${uuid7()}"),
                        conversationId = conversationId,
                        role = Conversation.Message.Role.USER,
                        content = listOf(
                            Conversation.Message.ContentItem.UserMessage(
                                buildPrompt(question, readResult)
                            )
                        ),
                        createdAt = Clock.System.now(),
                    )
                ),
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.READ_ANSWER_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.ReadQuestionAnswer,
                    toolContext = mapOf(
                        "memoryQuestionAnswer" to true,
                        "conversationId" to conversationId.value,
                    ),
                ),
            ),
            stageName = "read-question-answer",
            logContext = "conversation=${conversationId.value}",
            repairAttempts = 2,
            parse = { json.decodeFromString<ReadQuestionAnswerResponse>(it) },
            validate = { answer ->
                require(answer.answer.isNotBlank()) { "memory question answer must not be blank" }
                require(answer.sufficiency in setOf("answered", "insufficient", "conflicting")) {
                    "Unsupported memory question sufficiency: ${answer.sufficiency}"
                }
                validateAnswerMatchesExplicitReasoningConclusion(
                    answer = answer.answer,
                    reasoning = answer.reasoning,
                    sufficiency = answer.sufficiency,
                )
                validateAnsweredReasoningPreservesNamedQuestionTargets(
                    question = question,
                    reasoning = answer.reasoning,
                    sufficiency = answer.sufficiency,
                )
                validateAnsweredReasoningDoesNotAdmitQualifierMismatch(
                    reasoning = answer.reasoning,
                    sufficiency = answer.sufficiency,
                )
                validateElapsedLeadTimeAnswer(
                    question = question,
                    reasoning = answer.reasoning,
                    sufficiency = answer.sufficiency,
                )
                validateDistinctCategoryAnswerMembers(
                    question = question,
                    answer = answer.answer,
                    sufficiency = answer.sufficiency,
                )
            },
        )

        return response.value.toResult(readResult)
    }

    private fun buildPrompt(question: String, readResult: MemoryReadResult): String =
        """
        Memory stage: ReadQuestionAnswer v1.
        Current time: ${Clock.System.now()}

        Answer the user's direct memory question using only the selected persisted memory context below.
        Do not use hidden knowledge, benchmark labels, expected answers, general defaults, or guesses outside memory_context.
        If selected memory is insufficient or conflicting, say that directly and set sufficiency accordingly.
        The answer field must be non-empty for every sufficiency. Put the concrete answer there when memory supports it.
        When memory is insufficient, put a short "Memory is insufficient..." sentence in answer. When memory is conflicting,
        put a short "Memory is conflicting..." sentence in answer. Put the evidence reasoning in reasoning.
        For exact quote, exact wording, source, or when-said questions, prefer complete source text from memory_context over shorter evidence excerpts.
        ${MemoryReadPromptPolicy.answerSourceEvidenceRules()}
        For specifically qualified questions, preserve every required qualifier: person, role, date, venue, route, source, owner, medium, item type, component, material, feature, project, artifact, relationship, and scope. Job titles and roles introduced by "as" are required qualifiers. If retrieved memory only answers a weaker adjacent target, answer insufficient or conflicting instead of caveating a wrong value.
        ${MemoryReadPromptPolicy.answerDerivationAndConsistencyRules()}
        ${MemoryReadPromptPolicy.answerCountAndCategoryRules()}

        Question:
        $question

        Selected refs:
        ${readResult.renderSelectedRefsForAnswerPrompt()}

        Memory context:
        ```text
        ${readResult.runtimePrompt ?: "No relevant persisted memory was retrieved for the question."}
        ```
        """.trimIndent()

    @Serializable
    private data class ReadQuestionAnswerResponse(
        val answer: String,
        val reasoning: String,
        val sufficiency: String,
        @SerialName("evidence_refs")
        val evidenceRefs: List<String> = emptyList(),
        @SerialName("counted_items")
        val countedItems: List<String> = emptyList(),
        @SerialName("excluded_refs")
        val excludedRefs: List<String> = emptyList(),
    ) {
        fun toResult(readResult: MemoryReadResult): MemoryQuestionAnswerResult =
            MemoryQuestionAnswerResult(
                readResult = readResult,
                answer = answer.trim(),
                reasoning = reasoning.trim(),
                sufficiency = MemoryQuestionAnswerResult.Sufficiency.from(sufficiency),
                evidenceRefs = evidenceRefs.map { it.trim() }.filter { it.isNotBlank() },
                countedItems = countedItems.map { it.trim() }.filter { it.isNotBlank() },
                excludedRefs = excludedRefs.map { it.trim() }.filter { it.isNotBlank() },
            )
    }
}

private fun validateElapsedLeadTimeAnswer(
    question: String,
    reasoning: String,
    sufficiency: String,
) {
    if (sufficiency != "answered") return
    if (!question.asksForElapsedAgo()) return

    if (!reasoning.containsLeadTimeCue()) return
    if (reasoning.containsLeadTimeDerivationCue()) return

    error(
        "Elapsed-time answer uses a lead-time phrase without deriving it from an anchor. " +
            "For an 'ago' question, do not use N in advance/before/prior to as the final elapsed answer; " +
            "identify the anchor timing and combine offsets, or set sufficiency to insufficient."
    )
}

private fun validateAnsweredReasoningDoesNotAdmitQualifierMismatch(
    reasoning: String,
    sufficiency: String,
) {
    if (sufficiency != "answered") return
    if (!reasoning.admitsQuestionQualifierMismatch()) return

    error(
        "Answered memory question admits that selected memory answers a different qualifier than the question. " +
            "If the selected memory only contains an adjacent target, set sufficiency to insufficient instead of " +
            "returning the adjacent value."
    )
}

private fun validateAnsweredReasoningPreservesNamedQuestionTargets(
    question: String,
    reasoning: String,
    sufficiency: String,
) {
    if (sufficiency != "answered") return

    val reasoningNormalized = reasoning.normalizedAnswerForConsistency()
    val missingTargets = question.namedQuestionTargets()
        .filterNot { target -> reasoningNormalized.contains(target.normalizedAnswerForConsistency()) }
    if (missingTargets.isEmpty()) return

    error(
        "Answered memory question does not preserve named question target qualifiers: ${missingTargets.joinToString()}. " +
            "For qualified questions, reasoning must explicitly support the same named target. " +
            "If selected memory only supports a different adjacent target, set sufficiency to insufficient."
    )
}

private fun validateDistinctCategoryAnswerMembers(
    question: String,
    answer: String,
    sufficiency: String,
) {
    if (sufficiency != "answered") return
    if (!question.asksForDistinctCategoryMembers()) return

    val members = answer.answerListMembers()
    if (members.size < 2) return

    val duplicate = members
        .groupingBy { it }
        .eachCount()
        .entries
        .firstOrNull { it.value > 1 }
        ?: return

    error(
        "Answered memory question repeats category member '${duplicate.key}' in a distinct category list/order answer. " +
            "If the user asks for a category list or order, list each distinct category member once unless the question " +
            "explicitly asks for every occurrence, event, visit, trip, instance, or time."
    )
}

private fun validateAnswerMatchesExplicitReasoningConclusion(
    answer: String,
    reasoning: String,
    sufficiency: String,
) {
    if (sufficiency != "answered") return

    val conclusion = reasoning.explicitAnswerConclusion() ?: return
    val normalizedAnswer = answer.normalizedAnswerForConsistency()
    val normalizedConclusion = conclusion.normalizedAnswerForConsistency()
    if (normalizedAnswer.isBlank() || normalizedConclusion.isBlank()) return

    if (normalizedAnswer == normalizedConclusion) return
    if (normalizedAnswer in normalizedConclusion || normalizedConclusion in normalizedAnswer) return

    error(
        "Answer field contradicts the explicit reasoning conclusion. " +
            "The answer field is '$answer', but reasoning concludes '$conclusion'. " +
            "Return the explicit conclusion in answer, or set sufficiency to conflicting if memory is inconsistent."
    )
}

private fun String.explicitAnswerConclusion(): String? =
    explicitAnswerConclusionRegexes
        .firstNotNullOfOrNull { regex ->
            regex.find(this)?.groupValues?.getOrNull(1)
        }
        ?.trimAnswerConclusion()

private fun String.trimAnswerConclusion(): String {
    val delimiters = listOf("\n", ". ", "; ", " but ", " however ")
    val shortened = delimiters.fold(trim().trimAnswerQuotes()) { current, delimiter ->
        current.substringBefore(delimiter)
    }
    return shortened
        .trim()
        .trimEnd('.', ',', ';', ':')
        .trim()
        .trimAnswerQuotes()
}

private fun String.trimAnswerQuotes(): String =
    trim('"', '\'', '`', '“', '”', '‘', '’')

private fun String.normalizedAnswerForConsistency(): String =
    lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun String.asksForElapsedAgo(): Boolean =
    elapsedAgoQuestionRegex.containsMatchIn(this)

private fun String.asksForDistinctCategoryMembers(): Boolean =
    distinctCategoryQuestionRegex.containsMatchIn(this) &&
        !explicitOccurrenceQuestionRegex.containsMatchIn(this)

private fun String.answerListMembers(): List<String> =
    replace("→", ",")
        .replace(Regex("""\s+->\s+"""), ",")
        .split(Regex("""\s*(?:,|;|\n|\bthen\b|\bfinally\b|\bnext\b)\s*""", RegexOption.IGNORE_CASE))
        .map { item ->
            item
                .replace(Regex("""^\s*(?:first|second|third|fourth|fifth|\d+)\s*[:.)-]?\s*""", RegexOption.IGNORE_CASE), "")
                .trim()
                .trimEnd('.', ',', ';', ':')
                .normalizedAnswerForConsistency()
        }
        .filter { it.isNotBlank() }

private fun String.containsLeadTimeCue(): Boolean =
    leadTimeCueRegex.containsMatchIn(this)

private fun String.containsLeadTimeDerivationCue(): Boolean =
    leadTimeDerivationCueRegex.containsMatchIn(this)

private fun String.admitsQuestionQualifierMismatch(): Boolean =
    qualifierMismatchRegexes.any { it.containsMatchIn(this) }

private fun String.namedQuestionTargets(): List<String> =
    namedQuestionTargetRegex
        .findAll(this)
        .mapNotNull { match -> match.groupValues.getOrNull(1) }
        .map { target ->
            target
                .trim()
                .trimEnd('.', ',', ';', ':', '?', '!')
        }
        .filter { it.isNotBlank() }
        .distinctBy { it.normalizedAnswerForConsistency() }
        .toList()

private val elapsedAgoQuestionRegex = Regex("""\b(?:how many|how long|when)\b[\s\S]*\bago\b""", RegexOption.IGNORE_CASE)
private val distinctCategoryQuestionRegex =
    Regex("""\b(?:what|which)\b[\s\S]{0,80}\b(?:order|list)\s+of\b|\b(?:order|list)\s+of\b""", RegexOption.IGNORE_CASE)
private val explicitOccurrenceQuestionRegex =
    Regex("""\b(?:each|every|all)\s+(?:occurrences?|events?|visits?|trips?|flights?|instances?|times?|cases?)\b|\bevery\s+time\b""", RegexOption.IGNORE_CASE)
private val leadTimeCueRegex =
    Regex("""\b(?:\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|a|an)\s+(?:day|days|week|weeks|month|months|year|years)\s+(?:in advance|ahead of|prior to|before)\b""", RegexOption.IGNORE_CASE)
private val leadTimeDerivationCueRegex =
    Regex("""(?:\+|\b(?:add|adds|added|adding|plus|sum|sums|summed|combine|combines|combined|total|totals|together|altogether)\b)""", RegexOption.IGNORE_CASE)
private val namedQuestionTargetRegex =
    Regex("""\b(?:at|with|for|from|by|in|on|as)\s+([A-Z][A-Za-z0-9&.'-]*(?:\s+[A-Z][A-Za-z0-9&.'-]*){0,4})""")
private val qualifierMismatchRegexes = listOf(
    Regex(
        """\b(?:question|user asks?|asked target)\b[\s\S]{0,240}\b(?:names|asks for|asks about|specifies)\b[\s\S]{0,240}\bbut\b[\s\S]{0,240}\b(?:selected memory|selected refs|memory)\b[\s\S]{0,240}\b(?:only contains|only provides|only supports|supports a different|does not contain|does not provide|does not support|lacks|is missing)\b""",
        RegexOption.IGNORE_CASE,
    ),
    Regex(
        """\b(?:selected memory|selected refs|memory)\b[\s\S]{0,240}\b(?:only contains|only provides|only supports|supports a different|does not contain|does not provide|does not support|lacks|is missing)\b[\s\S]{0,240}\b(?:question|user asks?|asked target)\b""",
        RegexOption.IGNORE_CASE,
    ),
)
private val explicitAnswerConclusionRegexes = listOf(
    Regex("""\banswer field must match (?:the )?(?:computed )?conclusion:\s*([^\n.]+)""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:computed|final) conclusion:\s*([^\n.]+)""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:final answer|answer)\s+(?:is|should be|must be)\s*:?\s*([^\n.]+)""", RegexOption.IGNORE_CASE),
)

internal data class MemoryQuestionAnswerResult(
    val readResult: MemoryReadResult,
    val answer: String,
    val reasoning: String,
    val sufficiency: Sufficiency,
    val evidenceRefs: List<String>,
    val countedItems: List<String>,
    val excludedRefs: List<String>,
) {
    enum class Sufficiency {
        ANSWERED,
        INSUFFICIENT,
        CONFLICTING;

        companion object {
            fun from(value: String): Sufficiency =
                entries.firstOrNull { it.name == value.trim().uppercase() }
                    ?: error("Unsupported memory answer sufficiency: $value")
        }
    }
}

private fun MemoryReadResult.renderSelectedRefsForAnswerPrompt(): String {
    val hitsByRef = trace.selectedHits.associateBy { it.ref }
    val decisionsByRef = trace.selectorDecisions
        .filter { it.selected }
        .associateBy { it.ref }
    val refs = orderedSelectedRefs(hitsByRef, decisionsByRef)
    if (refs.isEmpty()) return "[]"

    return refs.joinToString("\n") { selectedRef ->
        val hit = selectedRef.hit
        buildString {
            append("- ${hit.ref.type.name}:${hit.ref.id}")
            selectedRef.decision?.let { append(" rank=${it.rank}") }
            hit.predicate?.let { append(" predicate=$it") }
            hit.status?.let { append(" status=$it") }
            append(" summary=${hit.summary.oneLineForMemoryAnswerPrompt()}")
            selectedRef.decision?.reason?.takeIf { it.isNotBlank() }?.let {
                append(" selection_reason=${it.oneLineForMemoryAnswerPrompt()}")
            }
        }
    }
}

private fun MemoryReadResult.orderedSelectedRefs(
    hitsByRef: Map<MemoryItemRef, MemoryReadTrace.Hit>,
    decisionsByRef: Map<MemoryItemRef, MemoryReadTrace.SelectorDecision>,
): List<MemoryQuestionSelectedRef> {
    val ordered = mutableListOf<MemoryQuestionSelectedRef>()
    val addedRefs = mutableSetOf<MemoryItemRef>()

    trace.selectorDecisions
        .asSequence()
        .filter { it.selected }
        .filter { it.rank > 0 && it.rank < Int.MAX_VALUE }
        .sortedWith(compareBy<MemoryReadTrace.SelectorDecision> { it.rank }
            .thenBy { it.ref.type.name }
            .thenBy { it.ref.id })
        .forEach { decision ->
            val hit = hitsByRef[decision.ref] ?: return@forEach
            if (addedRefs.add(hit.ref)) {
                ordered += MemoryQuestionSelectedRef(hit = hit, decision = decision)
            }
        }

    trace.selectedHits.forEach { hit ->
        if (addedRefs.add(hit.ref)) {
            ordered += MemoryQuestionSelectedRef(hit = hit, decision = decisionsByRef[hit.ref])
        }
    }

    return ordered.take(24)
}

private data class MemoryQuestionSelectedRef(
    val hit: MemoryReadTrace.Hit,
    val decision: MemoryReadTrace.SelectorDecision?,
)

private fun String.oneLineForMemoryAnswerPrompt(limit: Int = 500): String =
    replace(Regex("\\s+"), " ")
        .trim()
        .let { if (it.length <= limit) it else it.take(limit).trimEnd() + "..." }
