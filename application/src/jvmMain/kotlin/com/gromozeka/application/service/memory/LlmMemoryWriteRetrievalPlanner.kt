package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryPredicateCatalog
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryTimeWindow
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlanner
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LlmMemoryWriteRetrievalPlanner(
    private val runtime: AiRuntime,
    private val timezone: String,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryWriteRetrievalPlanner {
    private val log = KLoggers.logger(this)

    override suspend fun plan(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        predicateCatalog: MemoryPredicateCatalog,
    ): MemoryWriteRetrievalPlan {
        if (routeDecision.decision == MemoryRouteDecision.Decision.NOOP) {
            return MemoryWriteRetrievalPlan(needRetrieval = false)
        }

        val stageMessages = request.toMemoryStageMessages(
            stageName = "write-retrieval-planner",
            taskPrompt = buildPlannerUserPrompt(request, routeDecision, predicateCatalog),
        )

        log.info {
            "Memory write retrieval planner LLM call: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "threadContext=${request.memoryThreadContextSummaryForLog()} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size} stageMessages=${stageMessages.size}"
        }

        val response = runtime.callMemoryStageWithRetry(
            AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxTokens = 1_000,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.WriteRetrievalPlanner,
                    toolContext = mapOf(
                        "memoryWriteRetrievalPlanner" to true,
                        "memoryNamespace" to request.namespace.value,
                        "memorySourceId" to request.source.id.value,
                        "memoryRouteDecision" to routeDecision.decision.name,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "write-retrieval-planner",
            logContext = "namespace=${request.namespace.value} source=${request.source.id.value}",
        )

        val rawText = response.messages
            .flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
            .trim()

        log.info {
            "Memory write retrieval planner raw response: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "chars=${rawText.length} response=${rawText.oneLineForWriteRetrievalMemoryLog(4_000)}"
        }

        val jsonText = extractJsonObject(rawText)
            ?: throw IllegalStateException("Write-time retrieval planner did not return JSON: ${rawText.take(500)}")

        return json.decodeFromString<RetrievalPlannerResponse>(jsonText)
            .toPlan()
            .withRouteTaskGrounding(routeDecision)
    }

    private fun buildPlannerUserPrompt(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        predicateCatalog: MemoryPredicateCatalog,
    ): String = """
        Memory stage: WriteTimeRetrievalPlanner v3.
        Current time: ${Clock.System.now()}
        Timezone: $timezone
        Namespace: ${request.namespace.value}

        Router decision: ${routeDecision.decision.name}
        Router memory types: ${routeDecision.memoryTypes.joinToString { it.name }}
        Router reason: ${routeDecision.reason}

        Predicate catalog excerpt:
        ${predicateCatalog.renderForMemoryPrompt()}

        Stage instructions:
        $WRITE_TIME_RETRIEVAL_PLANNER_SYSTEM_PROMPT

        TARGET_MESSAGE source data:
        ${request.source.renderLatestTurn()}
    """.trimIndent()

    private fun MemorySource.renderLatestTurn(): String {
        val role = when (this) {
            is MemorySource.ChatTurn -> speakerRole.name
            is MemorySource.ToolOutput -> "TOOL"
            is MemorySource.DocumentChunk -> "DOCUMENT"
            is MemorySource.ImportedNote -> "IMPORT"
            is MemorySource.ExternalRecord -> "EXTERNAL"
        }

        return "[${id.value}] $role\n${contentText.limitForPlannerPrompt()}"
    }

    private fun String.limitForPlannerPrompt(maxChars: Int = 8_000): String {
        val trimmed = trim()
        if (trimmed.length <= maxChars) {
            return trimmed
        }
        return trimmed.take(maxChars) + "\n[truncated ${trimmed.length - maxChars} chars]"
    }

    private fun extractJsonObject(rawText: String): String? {
        val fenced = Regex("```(?:json)?\\s*(\\{.*\\})\\s*```", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(rawText)
            ?.groupValues
            ?.getOrNull(1)
        if (fenced != null) {
            return fenced.trim()
        }

        val start = rawText.indexOf('{')
        val end = rawText.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return rawText.substring(start, end + 1)
        }

        return null
    }

    @Serializable
    private data class RetrievalPlannerResponse(
        @SerialName("need_retrieval")
        val needRetrieval: Boolean = true,
        @SerialName("entity_queries")
        val entityQueries: List<String> = emptyList(),
        @SerialName("text_queries")
        val textQueries: List<String> = emptyList(),
        @SerialName("predicate_hints")
        val predicateHints: List<String> = emptyList(),
        @SerialName("memory_types")
        val memoryTypes: List<String> = emptyList(),
        @SerialName("time_filters")
        val timeFilters: TimeFilters = TimeFilters(),
        val limits: Limits = Limits(),
    ) {
        fun toPlan(): MemoryWriteRetrievalPlan =
            MemoryWriteRetrievalPlan(
                needRetrieval = needRetrieval,
                entityQueries = entityQueries.cleanQueries(),
                textQueries = textQueries.cleanQueries(),
                predicateHints = predicateHints.cleanQueries(),
                memoryTypes = memoryTypes
                    .mapNotNull { it.toMemorySemanticType() }
                    .toSet()
                    .withEntityCandidatesIfTypesWereSelected(needRetrieval),
                timeWindow = timeFilters.toMemoryTimeWindow(),
                retrievalBudget = MemoryRetrievalBudget(
                    claims = limits.claims.coerceIn(0, 20),
                    notes = limits.notes.coerceIn(0, 10),
                    tasks = limits.tasks.coerceIn(0, 10),
                    sources = limits.sources.coerceIn(0, 8),
                ),
            )
    }

    @Serializable
    private data class TimeFilters(
        @SerialName("from_iso")
        val fromIso: String? = null,
        @SerialName("to_iso")
        val toIso: String? = null,
    ) {
        fun toMemoryTimeWindow(): MemoryTimeWindow? {
            val from = fromIso?.takeIf { it.isNotBlank() }?.let { Instant.parse(it) }
            val to = toIso?.takeIf { it.isNotBlank() }?.let { Instant.parse(it) }
            if (from == null && to == null) {
                return null
            }
            return MemoryTimeWindow(from = from, to = to)
        }
    }

    @Serializable
    private data class Limits(
        val claims: Int = 0,
        val notes: Int = 0,
        val tasks: Int = 0,
        val sources: Int = 0,
    )

    private companion object {
        val WRITE_TIME_RETRIEVAL_PLANNER_SYSTEM_PROMPT = """
            You are WriteTimeRetrievalPlanner v3.

            Goal:
            Create a retrieval plan for memory update. Fetch only the memories and evidence most likely to help with deduplication, contradiction detection, scope alignment, and temporal updates.

            Return JSON:
            {
              "need_retrieval": true,
              "entity_queries": ["..."],
              "text_queries": ["..."],
              "predicate_hints": ["..."],
              "memory_types": ["profile", "claim", "note", "task", "source", "entity"],
              "time_filters": {
                "from_iso": null,
                "to_iso": null
              },
              "limits": {
                "claims": 20,
                "notes": 10,
                "tasks": 10,
                "sources": 8
              }
            }

            Rules:
            - Bias retrieval toward possible duplicates, contradictions, and relevant scope boundaries.
            - Use predicate catalog names in predicate_hints when a catalog predicate likely fits the target message.
            - Do not invent predicate synonyms in predicate_hints when an existing catalog predicate captures the same relation.
            - Include notes when the material looks rationale-heavy or plan-heavy.
            - Include claims when preferences, status, or factual updates are likely.
            - Include tasks when TARGET_MESSAGE may create, update, close, cancel, deduplicate, or discuss an explicit follow-up/task/deadline.
            - Include tasks when TARGET_MESSAGE says a remembered follow-up is done, finished, completed, closed, cancelled, no longer needed, blocked, unblocked, reprioritized, or changed.
            - Include entity retrieval for concrete people, products, projects, repos, files, technologies, and the stable user subject.
            - Add time filters when the turns mention "now", "before", "used to", "from now on", "no longer", deadlines, or relative dates.
            - Include source retrieval when conflicts or grounding-quality checks are likely to matter.
            - Keep the plan precise; fewer better queries are preferred.
            - Return valid JSON only.
        """.trimIndent()
    }
}

private fun Set<MemorySemanticType>.withEntityCandidatesIfTypesWereSelected(
    needRetrieval: Boolean,
): Set<MemorySemanticType> {
    if (!needRetrieval || isEmpty()) {
        return this
    }

    return this + MemorySemanticType.ENTITY
}

private fun MemoryWriteRetrievalPlan.withRouteTaskGrounding(
    routeDecision: MemoryRouteDecision,
): MemoryWriteRetrievalPlan {
    if (!needRetrieval || MemorySemanticType.TASK !in routeDecision.memoryTypes) {
        return this
    }

    return copy(
        memoryTypes = memoryTypes + MemorySemanticType.TASK + MemorySemanticType.ENTITY,
        retrievalBudget = retrievalBudget.copy(tasks = maxOf(retrievalBudget.tasks, 8)),
    )
}

private fun List<String>.cleanQueries(): List<String> =
    map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

private fun String.oneLineForWriteRetrievalMemoryLog(maxChars: Int): String {
    val normalized = replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= maxChars) {
        return normalized
    }
    return normalized.take(maxChars) + "...[truncated ${normalized.length - maxChars} chars]"
}
