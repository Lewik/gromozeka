package com.gromozeka.infrastructure.ai.memory

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.gromozeka.infrastructure.ai.springai.ChatModelFactory
import com.gromozeka.domain.model.memory.EntityType
import com.gromozeka.domain.model.memory.EntityTypesConfig
import com.gromozeka.infrastructure.ai.memory.models.NodeResolutions
import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.repository.KnowledgeGraphRepository
import klog.KLoggers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.serialization.json.Json
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.util.*

@Service
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class EntityDeduplicationService(
    private val knowledgeGraphRepository: KnowledgeGraphRepository,
    @Lazy
    private val chatModelFactory: ChatModelFactory,
    @Value("\${gromozeka.ai.provider:CLAUDE_CODE}")
    private val aiProvider: String,
    @Value("\${gromozeka.ai.gemini.model:gemini-2.0-flash-thinking-exp-01-21}")
    private val geminiModel: String,
    @Value("\${gromozeka.ai.claude.model:claude-sonnet-4-5}")
    private val claudeModel: String,
    @Value("\${gromozeka.ai.anthropic.model:sonnet}")
    private val anthropicModel: String,
    @Value("\${gromozeka.ai.ollama.model:llama3}")
    private val ollamaModel: String
) {
    private val log = KLoggers.logger(this)
    private val objectMapper = ObjectMapper()
    private val entityTypes: List<EntityType>
    private val groupId = "dev-user"

    init {
        val configJson = this::class.java.classLoader
            .getResourceAsStream("memory/entity-types.json")
            ?.bufferedReader()
            ?.readText()
            ?: throw IllegalStateException("entity-types.json not found")

        val config = Json.decodeFromString<EntityTypesConfig>(configJson)
        entityTypes = config.entityTypes
    }

    private fun getChatModel() = chatModelFactory.get(
        provider = AIProvider.valueOf(aiProvider),
        modelName = when (AIProvider.valueOf(aiProvider)) {
            AIProvider.GEMINI -> geminiModel
            AIProvider.CLAUDE_CODE -> claudeModel
            AIProvider.OLLAMA -> ollamaModel
            AIProvider.OPEN_AI -> TODO()
            AIProvider.ANTHROPIC -> anthropicModel
        },
        projectPath = null
    )

    suspend fun deduplicateExtractedEntities(
        extractedEntities: List<Pair<String, Int>>,
        content: String,
        previousMessages: String
    ): Map<Int, String> {
        if (extractedEntities.isEmpty()) {
            log.warn { "No entities to deduplicate" }
            return extractedEntities.indices.associateWith { UUID.randomUUID().toString() }
        }

        log.info { "Starting deduplication for ${extractedEntities.size} extracted entities" }

        val extractedNames = extractedEntities.map { it.first }
        val candidates = DedupHelpers.collectCandidates(
            knowledgeGraphRepository = knowledgeGraphRepository,
            groupId = groupId,
            extractedNames = extractedNames
        )

        log.debug { "Collected ${candidates.size} candidate entities from Neo4j for deduplication" }

        val deterministicMatches = DedupHelpers.deterministicMatching(
            extractedNames = extractedNames,
            candidates = candidates
        )

        log.info {
            "Deterministic matching resolved ${deterministicMatches.size} of ${extractedEntities.size} entities " +
            "(${deterministicMatches.count { it.value.matchType == MatchType.EXACT }} exact, " +
            "${deterministicMatches.count { it.value.matchType == MatchType.FUZZY }} fuzzy)"
        }

        val unresolvedIndices = extractedEntities.indices.filter { !deterministicMatches.containsKey(it) }

        if (unresolvedIndices.isEmpty()) {
            log.info { "All entities resolved via deterministic matching, no LLM deduplication needed" }
            return buildUuidMapping(extractedEntities, deterministicMatches, emptyMap())
        }

        log.info { "Running LLM-based deduplication for ${unresolvedIndices.size} unresolved entities" }

        val llmMatches = llmBasedDeduplication(
            unresolvedIndices = unresolvedIndices,
            extractedEntities = extractedEntities,
            candidates = candidates,
            content = content,
            previousMessages = previousMessages
        )

        log.info { "LLM deduplication resolved ${llmMatches.size} additional entities" }

        val totalResolved = deterministicMatches.size + llmMatches.size
        log.info {
            "Deduplication complete: $totalResolved of ${extractedEntities.size} entities resolved as duplicates"
        }

        return buildUuidMapping(extractedEntities, deterministicMatches, llmMatches)
    }

    suspend fun llmBasedDeduplication(
        unresolvedIndices: List<Int>,
        extractedEntities: List<Pair<String, Int>>,
        candidates: List<EntityCandidate>,
        content: String,
        previousMessages: String
    ): Map<Int, DedupMatch> {
        if (unresolvedIndices.isEmpty()) return emptyMap()

        val extractedForPrompt = unresolvedIndices.map { index ->
            val (name, entityTypeId) = extractedEntities[index]
            val entityType = entityTypes.find { it.id == entityTypeId }?.name ?: "Unknown"
            mapOf(
                "id" to index,
                "name" to name,
                "entity_type" to listOf("Entity", entityType)
            )
        }

        val existingForPrompt = candidates.mapIndexed { idx, candidate ->
            mapOf(
                "idx" to idx,
                "name" to candidate.name,
                "entity_types" to listOf("Entity", candidate.entityType),
                "summary" to candidate.summary
            )
        }

        val prompt = MemoryExtractionPrompts.deduplicateEntitiesPrompt(
            extractedEntities = extractedForPrompt,
            existingEntities = existingForPrompt,
            episodeContent = content,
            previousMessages = previousMessages
        )

        val chatModel = getChatModel()

        val response = chatModel.stream(Prompt(UserMessage(prompt))).collectList().awaitSingle().lastOrNull()
            ?.result?.output?.text
            ?: return emptyMap()

        log.debug { "LLM deduplication response: ${response.take(500)}..." }

        return parseLlmDedupResponse(response, unresolvedIndices, candidates)
    }

    fun parseLlmDedupResponse(
        response: String,
        unresolvedIndices: List<Int>,
        candidates: List<EntityCandidate>
    ): Map<Int, DedupMatch> {
        return try {
            var cleanResponse = response.trim()
            if (cleanResponse.startsWith("```json")) {
                cleanResponse = cleanResponse.removePrefix("```json").trim()
            } else if (cleanResponse.startsWith("```")) {
                cleanResponse = cleanResponse.removePrefix("```").trim()
            }
            if (cleanResponse.endsWith("```")) {
                cleanResponse = cleanResponse.removeSuffix("```").trim()
            }

            val jsonStart = cleanResponse.indexOf("{")
            val jsonEnd = cleanResponse.lastIndexOf("}") + 1

            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                log.warn { "No JSON found in LLM deduplication response" }
                return emptyMap()
            }

            val jsonStr = cleanResponse.substring(jsonStart, jsonEnd)
            val resolutions = objectMapper.readValue<NodeResolutions>(jsonStr)

            val matches = mutableMapOf<Int, DedupMatch>()
            for (resolution in resolutions.entityResolutions) {
                if (!unresolvedIndices.contains(resolution.id)) {
                    log.warn { "LLM returned resolution for entity ${resolution.id} which was not in unresolved list" }
                    continue
                }

                if (resolution.duplicateIdx >= 0 && resolution.duplicateIdx < candidates.size) {
                    matches[resolution.id] = DedupMatch(
                        extractedIndex = resolution.id,
                        candidateUuid = candidates[resolution.duplicateIdx].uuid,
                        matchType = MatchType.LLM,
                        confidence = 1.0
                    )
                }
            }

            matches
        } catch (e: Exception) {
            log.error(e) { "Failed to parse LLM deduplication response: $response" }
            emptyMap()
        }
    }

    fun buildUuidMapping(
        extractedEntities: List<Pair<String, Int>>,
        deterministicMatches: Map<Int, DedupMatch>,
        llmMatches: Map<Int, DedupMatch>
    ): Map<Int, String> {
        val allMatches = deterministicMatches + llmMatches
        return extractedEntities.indices.associateWith { index ->
            allMatches[index]?.candidateUuid ?: UUID.randomUUID().toString()
        }
    }
}
