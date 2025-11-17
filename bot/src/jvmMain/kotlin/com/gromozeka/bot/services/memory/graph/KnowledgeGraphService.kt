package com.gromozeka.bot.services.memory.graph

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.gromozeka.bot.services.memory.graph.models.*
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import com.gromozeka.bot.services.ChatModelFactory
import com.gromozeka.bot.services.SettingsService
import com.gromozeka.bot.settings.AIProvider
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.*

@Service
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class KnowledgeGraphService(
    private val neo4jDriver: Driver?,
    private val chatModelFactory: ChatModelFactory,
    private val settingsService: SettingsService,
    private val embeddingModel: EmbeddingModel,
    private val rerankService: RerankService
) {
    private val log = KLoggers.logger(this)
    private val objectMapper = ObjectMapper()
    private val entityTypes: List<EntityType>
    private val groupId = "dev-user"

    companion object {
        private const val MAX_SUMMARY_CHARS = 250
        private const val MAX_REFLEXION_ITERATIONS = 2
    }

    init {
        val configJson = this::class.java.classLoader
            .getResourceAsStream("memory/entity-types.json")
            ?.bufferedReader()
            ?.readText()
            ?: throw IllegalStateException("entity-types.json not found")

        val config = Json.decodeFromString<EntityTypesConfig>(configJson)
        entityTypes = config.entity_types
    }

    /**
     * Truncate text at or about maxChars while respecting sentence boundaries.
     * Attempts to truncate at the last complete sentence before maxChars.
     * If no sentence boundary is found before maxChars, truncates at maxChars.
     */
    private fun truncateAtSentence(text: String, maxChars: Int): String {
        if (text.length <= maxChars) {
            return text
        }

        val truncated = text.substring(0, maxChars)

        // Find sentence boundaries: period, exclamation, or question mark followed by space or end
        val sentencePattern = Regex("[.!?](?:\\s|$)")
        val matches = sentencePattern.findAll(truncated).toList()

        return if (matches.isNotEmpty()) {
            // Truncate at the last sentence boundary found
            val lastMatch = matches.last()
            text.substring(0, lastMatch.range.last + 1).trim()
        } else {
            // No sentence boundary found, truncate at maxChars
            truncated.trim()
        }
    }

    suspend fun extractAndSaveToGraph(
        content: String,
        previousMessages: String = "",
        referenceTime: Instant = Clock.System.now(),
        episodeId: String? = null
    ): String = coroutineScope {
        log.info { "Starting knowledge extraction from content (${content.length} chars)" }
        
        if (neo4jDriver == null) {
            log.warn { "Neo4j driver not available, cannot extract to graph" }
            return@coroutineScope "Neo4j driver not available"
        }

        val entities = extractEntities(content, previousMessages)
        log.debug { "Extracted ${entities.size} entities, starting deduplication" }

        val uuidMapping = deduplicateExtractedEntities(
            extractedEntities = entities,
            content = content,
            previousMessages = previousMessages
        )

        log.debug { "Creating ${entities.size} entity nodes with deduplicated UUIDs" }

        val entityNodes = buildList {
            for ((index, entity) in entities.withIndex()) {
                val entityType = entityTypes.find { it.id == entity.second }?.name ?: "Unknown"
                val embedding = embeddingModel.embed(entity.first)
                val uuid = uuidMapping[index] ?: UUID.randomUUID().toString()
                
                // Get existing summary if entity already exists in graph (for incremental updates)
                val existingSummary = if (neo4jDriver != null && uuidMapping.containsKey(index)) {
                    try {
                        neo4jDriver.session().use { session ->
                            val result = session.run(
                                """
                                MATCH (n:MemoryObject {uuid: ${'$'}uuid, group_id: ${'$'}groupId})
                                RETURN n.summary as summary
                                """.trimIndent(),
                                mapOf("uuid" to uuid, "groupId" to groupId)
                            )
                            if (result.hasNext()) {
                                result.next()["summary"]?.asString("")?.takeIf { it.isNotBlank() }
                            } else {
                                null
                            }
                        }
                    } catch (e: Exception) {
                        log.warn(e) { "Failed to fetch existing summary for $uuid: ${e.message}" }
                        null
                    }
                } else {
                    null
                }
                
                val summary = generateEntitySummary(entity.first, entityType, content, existingSummary)

                add(MemoryObject(
                    uuid = uuid,
                    name = entity.first,
                    embedding = embedding.toList(),
                    summary = summary,
                    groupId = groupId,
                    labels = listOf(entityType),
                    createdAt = referenceTime
                ))
            }
        }

        val relationships = if (entityNodes.size >= 2) {
            extractRelationships(content, entityNodes, referenceTime, episodeId)
        } else {
            log.warn { "Only ${entityNodes.size} entities extracted, skipping relationship extraction (need at least 2)" }
            emptyList()
        }

        saveToNeo4j(entityNodes, relationships)

        val result = "Added ${entityNodes.size} entities and ${relationships.size} relationships to knowledge graph"
        log.info { result }
        return@coroutineScope result
    }

    private suspend fun extractEntities(
        content: String,
        previousMessages: String
    ): List<Pair<String, Int>> {
        log.debug { "Extracting entities from content (${content.length} chars) with reflexion (max iterations: $MAX_REFLEXION_ITERATIONS)" }

        var reflexionIteration = 0
        var entitiesMissed = true
        var customPrompt = ""
        var entities = emptyList<Pair<String, Int>>()

        while (entitiesMissed && reflexionIteration <= MAX_REFLEXION_ITERATIONS) {
            val prompt = MemoryExtractionPrompts.extractEntitiesPrompt(
                content = content,
                entityTypes = entityTypes,
                previousMessages = previousMessages,
                customPrompt = customPrompt
            )
            
            log.info { "=== ENTITY EXTRACTION PROMPT (iteration $reflexionIteration) ===" }
            log.info { "Content length: ${content.length} chars" }
            log.info { "Full prompt:\n$prompt" }

            val settings = settingsService.settings
            val chatModel = chatModelFactory.get(
                provider = settings.defaultAiProvider,
                modelName = when (settings.defaultAiProvider) {
                    AIProvider.GEMINI -> settings.geminiModel
                    AIProvider.CLAUDE_CODE -> settings.claudeModel ?: "claude-sonnet-4-5"
                    AIProvider.OLLAMA -> settings.ollamaModel
                },
                projectPath = null
            )

            val response = chatModel.stream(Prompt(UserMessage(prompt))).collectList().awaitSingle().lastOrNull()
                ?.result?.output?.text

            log.info { "=== ENTITY EXTRACTION RESPONSE (iteration $reflexionIteration) ===" }
            log.info { "Response length: ${response?.length ?: 0} chars" }
            log.info { "Full response:\n$response" }
            log.debug { "LLM response for entity extraction (iteration $reflexionIteration): ${response?.take(200)}..." }

            entities = parseEntitiesResponse(response ?: "")
            log.info { "Extracted ${entities.size} entities from content (iteration $reflexionIteration)" }

            reflexionIteration++

            if (reflexionIteration < MAX_REFLEXION_ITERATIONS) {
                val missedEntities = checkMissedEntities(content, previousMessages, entities.map { it.first })

                if (missedEntities.isNotEmpty()) {
                    entitiesMissed = true
                    customPrompt = "Make sure that the following entities are extracted:\n" +
                        missedEntities.joinToString("\n") { "- $it" }
                    log.info { "Reflexion detected ${missedEntities.size} missed entities: $missedEntities. Running extraction iteration ${reflexionIteration + 1}" }
                } else {
                    entitiesMissed = false
                    log.info { "Reflexion found no missed entities. Stopping after $reflexionIteration iterations" }
                }
            } else {
                entitiesMissed = false
                log.info { "Reached max reflexion iterations ($MAX_REFLEXION_ITERATIONS). Final entity count: ${entities.size}" }
            }
        }

        return entities
    }

    private suspend fun checkMissedEntities(
        content: String,
        previousMessages: String,
        extractedEntityNames: List<String>
    ): List<String> {
        if (extractedEntityNames.isEmpty()) {
            log.debug { "No entities extracted yet, skipping reflexion check" }
            return emptyList()
        }

        val prompt = MemoryExtractionPrompts.reflexionPrompt(
            content = content,
            previousMessages = previousMessages,
            extractedEntityNames = extractedEntityNames
        )

        val settings = settingsService.settings
        val chatModel = chatModelFactory.get(
            provider = settings.defaultAiProvider,
            modelName = when (settings.defaultAiProvider) {
                AIProvider.GEMINI -> settings.geminiModel
                AIProvider.CLAUDE_CODE -> settings.claudeModel ?: "claude-sonnet-4-5"
                AIProvider.OLLAMA -> settings.ollamaModel
            },
            projectPath = null
        )

        val response = chatModel.stream(Prompt(UserMessage(prompt))).collectList().awaitSingle().lastOrNull()
            ?.result?.output?.text
            ?: return emptyList()

        log.debug { "Reflexion LLM response: ${response.take(200)}..." }

        return parseMissedEntitiesResponse(response)
    }

    private fun parseMissedEntitiesResponse(response: String): List<String> {
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
                log.warn { "No JSON found in reflexion response" }
                return emptyList()
            }

            val jsonStr = cleanResponse.substring(jsonStart, jsonEnd)
            val missedEntities = objectMapper.readValue<MissedEntities>(jsonStr)

            missedEntities.missedEntities
        } catch (e: Exception) {
            log.error(e) { "Failed to parse missed entities from response: $response" }
            emptyList()
        }
    }

    private suspend fun generateEntitySummary(
        entityName: String,
        entityType: String,
        content: String,
        existingSummary: String? = null
    ): String {
        log.debug { "${if (existingSummary != null) "Updating" else "Generating"} summary for entity: $entityName ($entityType)" }

        val prompt = MemoryExtractionPrompts.generateEntitySummaryPrompt(
            entityName = entityName,
            entityType = entityType,
            content = content,
            existingSummary = existingSummary
        )

        val settings = settingsService.settings
        val chatModel = chatModelFactory.get(
            provider = settings.defaultAiProvider,
            modelName = when (settings.defaultAiProvider) {
                AIProvider.GEMINI -> settings.geminiModel
                AIProvider.CLAUDE_CODE -> settings.claudeModel ?: "claude-sonnet-4-5"
                AIProvider.OLLAMA -> settings.ollamaModel
            },
            projectPath = null
        )

        val response = chatModel.stream(Prompt(UserMessage(prompt))).collectList().awaitSingle().lastOrNull()
            ?.result?.output?.text

        val summary = response?.trim()?.let { truncateAtSentence(it, MAX_SUMMARY_CHARS) } ?: ""
        log.debug { "Generated summary for $entityName: $summary" }

        return summary
    }

    private fun parseEntitiesResponse(response: String): List<Pair<String, Int>> {
        log.info { "=== PARSING ENTITIES RESPONSE ===" }
        log.info { "Raw response length: ${response.length}" }
        log.info { "Raw response: $response" }
        
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
                log.warn { "No JSON found in entity extraction response: ${cleanResponse.take(100)}..." }
                return emptyList()
            }

            val jsonStr = cleanResponse.substring(jsonStart, jsonEnd)
            log.debug { "Parsing entities JSON (${jsonStr.length} chars): ${jsonStr.take(300)}..." }
            
            val json: JsonNode = objectMapper.readTree(jsonStr)
            val entities = json.get("extracted_entities")
            
            if (entities == null) {
                log.warn { "No 'extracted_entities' field in response JSON" }
                return emptyList()
            }

            entities.map { entity ->
                val name = entity.get("name")?.asText() ?: return@map null
                val typeId = entity.get("entity_type_id")?.asInt() ?: return@map null
                name to typeId
            }.filterNotNull()
        } catch (e: Exception) {
            log.error(e) { "Failed to parse entities from response: ${response.take(200)}..." }
            emptyList()
        }
    }

    private suspend fun extractRelationships(
        content: String,
        entityNodes: List<MemoryObject>,
        referenceTime: Instant,
        episodeId: String?
    ): List<MemoryLink> {
        log.debug { "Extracting relationships between ${entityNodes.size} entities" }
        
        val entitiesWithIds = entityNodes.mapIndexed { index, node -> (index + 1) to node.name }
        
        val prompt = MemoryExtractionPrompts.extractRelationshipsPrompt(
            content = content,
            entities = entitiesWithIds,
            referenceTime = referenceTime
        )
        
        log.info { "=== RELATIONSHIP EXTRACTION PROMPT ===" }
        log.info { "Prompt length: ${prompt.length} chars" }
        log.info { "Entities count: ${entityNodes.size}" }
        log.info { "Content: ${content.take(500)}..." }

        val settings = settingsService.settings
        val chatModel = chatModelFactory.get(
            provider = settings.defaultAiProvider,
            modelName = when (settings.defaultAiProvider) {
                AIProvider.GEMINI -> settings.geminiModel
                AIProvider.CLAUDE_CODE -> settings.claudeModel ?: "claude-sonnet-4-5"
                AIProvider.OLLAMA -> settings.ollamaModel
            },
            projectPath = null
        )
        
        val response = chatModel.stream(Prompt(UserMessage(prompt))).collectList().awaitSingle().lastOrNull()
            ?.result?.output?.text

        log.info { "=== RELATIONSHIP EXTRACTION RESPONSE ===" }
        log.info { "Response: ${response ?: "NULL"}" }
        log.debug { "LLM response for relationship extraction (full): $response" }

        val relationships = parseRelationshipsResponse(response ?: "", entityNodes, referenceTime, episodeId)
        log.info { "Extracted ${relationships.size} relationships from content" }
        
        return relationships
    }

    private suspend fun parseRelationshipsResponse(
        response: String,
        entityNodes: List<MemoryObject>,
        referenceTime: Instant,
        episodeId: String?
    ): List<MemoryLink> {
        log.info { "=== PARSING RELATIONSHIPS RESPONSE ===" }
        log.info { "Raw response length: ${response.length}" }
        log.info { "Raw response: $response" }
        log.info { "Entity nodes count: ${entityNodes.size}" }
        
        return try {
            log.debug { "Raw response before parsing (${response.length} chars): ${response.take(500)}..." }
            
            var cleanResponse = response.trim()
            if (cleanResponse.startsWith("```json")) {
                cleanResponse = cleanResponse.removePrefix("```json").trim()
            } else if (cleanResponse.startsWith("```")) {
                cleanResponse = cleanResponse.removePrefix("```").trim()
            }
            if (cleanResponse.endsWith("```")) {
                cleanResponse = cleanResponse.removeSuffix("```").trim()
            }
            
            log.debug { "After markdown removal (${cleanResponse.length} chars): ${cleanResponse.take(500)}..." }
            
            val jsonStart = cleanResponse.indexOf("{")
            val jsonEnd = cleanResponse.lastIndexOf("}") + 1
            
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                log.warn { "No JSON found in relationship extraction response. Full response: $cleanResponse" }
                return emptyList()
            }

            val jsonStr = cleanResponse.substring(jsonStart, jsonEnd)
            log.debug { "Extracted JSON string (${jsonStr.length} chars): $jsonStr" }
            
            val json: JsonNode = objectMapper.readTree(jsonStr)
            val edges = json.get("edges")
            
            if (edges == null) {
                log.warn { "No 'edges' field in response JSON. Available fields: ${json.fieldNames().asSequence().toList()}" }
                return emptyList()
            }

            val edgesList = edges.toList()
            log.debug { "Processing ${edgesList.size} edges from JSON" }

            buildList {
                for (edge in edgesList) {
                    val sourceId = edge.get("source_entity_id")?.asInt()
                    val targetId = edge.get("target_entity_id")?.asInt()
                    
                    if (sourceId == null || targetId == null) {
                        log.warn { "Missing or invalid entity IDs: source=$sourceId, target=$targetId, edge fields: ${edge.fieldNames().asSequence().toList()}" }
                        continue
                    }
                    
                    if (sourceId < 1 || sourceId > entityNodes.size || 
                        targetId < 1 || targetId > entityNodes.size) {
                        log.warn { "Invalid entity IDs in edge: source=$sourceId, target=$targetId (entities: ${entityNodes.size})" }
                        continue
                    }

                    val sourceNode = entityNodes[sourceId - 1]
                    val targetNode = entityNodes[targetId - 1]
                    val relationType = edge.get("relation_type")?.asText() ?: continue
                    val fact = edge.get("fact")?.asText() ?: continue
                    val validAtStr = edge.get("valid_at")?.asText()
                    val invalidAtStr = edge.get("invalid_at")?.asText()

                    val embedding = embeddingModel.embed(fact)

                    add(MemoryLink(
                        uuid = UUID.randomUUID().toString(),
                        sourceNodeUuid = sourceNode.uuid,
                        targetNodeUuid = targetNode.uuid,
                        relationType = relationType,
                        description = fact,
                        embedding = embedding.toList(),
                        validAt = parseInstant(validAtStr),
                        invalidAt = parseInstant(invalidAtStr),
                        createdAt = referenceTime,
                        expiredAt = null,
                        sources = if (episodeId != null) listOf(episodeId) else emptyList(),
                        groupId = groupId
                    ))
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to parse relationships from response. Full response: $response. Exception: ${e.message}" }
            emptyList()
        }
    }

    private suspend fun deduplicateExtractedEntities(
        extractedEntities: List<Pair<String, Int>>,
        content: String,
        previousMessages: String
    ): Map<Int, String> {
        if (neo4jDriver == null || extractedEntities.isEmpty()) {
            log.warn { "Neo4j driver unavailable or no entities to deduplicate" }
            return extractedEntities.indices.associateWith { UUID.randomUUID().toString() }
        }

        log.info { "Starting deduplication for ${extractedEntities.size} extracted entities" }

        val extractedNames = extractedEntities.map { it.first }
        val candidates = DedupHelpers.collectCandidates(
            neo4jDriver = neo4jDriver,
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

    private suspend fun llmBasedDeduplication(
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

        val settings = settingsService.settings
        val chatModel = chatModelFactory.get(
            provider = settings.defaultAiProvider,
            modelName = when (settings.defaultAiProvider) {
                AIProvider.GEMINI -> settings.geminiModel
                AIProvider.CLAUDE_CODE -> settings.claudeModel ?: "claude-sonnet-4-5"
                AIProvider.OLLAMA -> settings.ollamaModel
            },
            projectPath = null
        )

        val response = chatModel.stream(Prompt(UserMessage(prompt))).collectList().awaitSingle().lastOrNull()
            ?.result?.output?.text
            ?: return emptyMap()

        log.debug { "LLM deduplication response: ${response.take(500)}..." }

        return parseLlmDedupResponse(response, unresolvedIndices, candidates)
    }

    private fun parseLlmDedupResponse(
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

    private fun buildUuidMapping(
        extractedEntities: List<Pair<String, Int>>,
        deterministicMatches: Map<Int, DedupMatch>,
        llmMatches: Map<Int, DedupMatch>
    ): Map<Int, String> {
        val allMatches = deterministicMatches + llmMatches
        return extractedEntities.indices.associateWith { index ->
            allMatches[index]?.candidateUuid ?: UUID.randomUUID().toString()
        }
    }

    private suspend fun saveToNeo4j(
        entityNodes: List<MemoryObject>,
        relationships: List<MemoryLink>
    ) {
        if (neo4jDriver == null) {
            log.warn { "Neo4j driver is null, cannot save to graph" }
            return
        }

        log.debug { "Saving ${entityNodes.size} nodes and ${relationships.size} edges to Neo4j" }

        try {
            neo4jDriver.session().use { session ->
                entityNodes.forEach { node ->
                    val query = """
                        MERGE (n:MemoryObject {uuid: ${'$'}uuid})
                        SET n.name = ${'$'}name,
                            n.embedding = ${'$'}embedding,
                            n.summary = ${'$'}summary,
                            n.group_id = ${'$'}groupId,
                            n.labels = ${'$'}labels,
                            n.created_at = datetime(${'$'}createdAt)
                    """.trimIndent()

                    session.run(
                        query,
                        mapOf(
                            "uuid" to node.uuid,
                            "name" to node.name,
                            "embedding" to node.embedding,
                            "summary" to node.summary,
                            "groupId" to node.groupId,
                            "labels" to node.labels,
                            "createdAt" to node.createdAt.toString()
                        )
                    )
                }

                relationships.forEach { edge ->
                    val query = """
                        MATCH (source:MemoryObject {uuid: ${'$'}sourceUuid})
                        MATCH (target:MemoryObject {uuid: ${'$'}targetUuid})
                        MERGE (source)-[r:LINKS_TO {uuid: ${'$'}uuid}]->(target)
                        SET r.description = ${'$'}description,
                            r.description_embedding = ${'$'}embedding,
                            r.valid_at = datetime(${'$'}validAt),
                            r.invalid_at = datetime(${'$'}invalidAt),
                            r.created_at = datetime(${'$'}createdAt),
                            r.sources = ${'$'}sources,
                            r.group_id = ${'$'}groupId
                    """.trimIndent()

                    session.run(
                        query,
                        mapOf(
                            "uuid" to edge.uuid,
                            "sourceUuid" to edge.sourceNodeUuid,
                            "targetUuid" to edge.targetNodeUuid,
                            "description" to edge.description,
                            "embedding" to edge.embedding,
                            "validAt" to edge.validAt?.toString(),
                            "invalidAt" to edge.invalidAt?.toString(),
                            "createdAt" to edge.createdAt.toString(),
                            "sources" to edge.sources,
                            "groupId" to edge.groupId
                        )
                    )
                }
            }
            log.info { "Successfully saved ${entityNodes.size} nodes and ${relationships.size} edges to Neo4j" }
        } catch (e: Exception) {
            log.error(e) { "Failed to save to Neo4j: ${e.message}" }
            throw e
        }
    }

    suspend fun hybridSearch(
        query: String,
        limit: Int = 5,
        useReranking: Boolean = false,
        useVectorIndex: Boolean = true,
        asOf: Instant? = null
    ): Map<String, Any> = coroutineScope {
        if (neo4jDriver == null) {
            return@coroutineScope mapOf("error" to "Neo4j not available")
        }

        val candidateLimit = if (useReranking) maxOf(limit * 5, 50) else limit

        val results = listOf(
            async { bm25Search(query, candidateLimit) },
            async { vectorSimilaritySearch(query, candidateLimit, useVectorIndex) },
            async { graphTraversal(query, candidateLimit, asOf) }
        ).awaitAll()

        val candidates = results.flatten().distinctBy { it["uuid"] }

        val finalResults = if (useReranking && candidates.isNotEmpty()) {
            val documents = candidates.map { result ->
                val name = result["name"] as? String ?: ""
                val summary = result["summary"] as? String ?: ""
                "$name: $summary"
            }

            val rerankResults = rerankService.rerank(query, documents, topK = limit)

            rerankResults.map { rerankResult ->
                candidates[rerankResult.index]
            }
        } else {
            candidates.take(limit)
        }

        mapOf(
            "results" to finalResults,
            "count" to finalResults.size
        )
    }

    private fun bm25Search(query: String, limit: Int): List<Map<String, Any>> {
        if (neo4jDriver == null) return emptyList()

        return try {
            neo4jDriver.session().use { session ->
                val result = session.run(
                    """
                    CALL db.index.fulltext.queryNodes('memory_object_index', ${'$'}query)
                    YIELD node, score
                    WHERE node.group_id = ${'$'}groupId
                    RETURN node.uuid AS uuid, node.name AS name, node.summary AS summary, score
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                    mapOf("query" to query, "groupId" to groupId, "limit" to limit)
                )

                result.list { record ->
                    mapOf(
                        "uuid" to record.get("uuid").asString(),
                        "name" to record.get("name").asString(),
                        "summary" to record.get("summary").asString(""),
                        "score" to record.get("score").asDouble()
                    )
                }
            }
        } catch (e: Exception) {
            log.warn(e) { "BM25 search failed: ${e.message}" }
            emptyList()
        }
    }

    private fun graphTraversal(query: String, limit: Int, asOf: Instant? = null): List<Map<String, Any>> {
        if (neo4jDriver == null) return emptyList()

        return try {
            neo4jDriver.session().use { session ->
                val temporalFilter = if (asOf != null) {
                    """
                    AND ALL(rel IN r WHERE
                        datetime(rel.valid_at) <= datetime(${'$'}asOf)
                        AND (rel.invalid_at IS NULL OR datetime(rel.invalid_at) > datetime(${'$'}asOf))
                    )
                    """.trimIndent()
                } else {
                    ""
                }

                val params = mutableMapOf<String, Any>(
                    "query" to query,
                    "groupId" to groupId,
                    "limit" to limit
                )
                if (asOf != null) {
                    params["asOf"] = asOf.toString()
                }

                val result = session.run(
                    """
                    MATCH (n:MemoryObject)-[r:LINKS_TO*1..2]-(connected:MemoryObject)
                    WHERE n.group_id = ${'$'}groupId
                      AND connected.group_id = ${'$'}groupId
                      AND (n.name CONTAINS ${'$'}query OR connected.name CONTAINS ${'$'}query)
                      $temporalFilter
                    RETURN DISTINCT connected.uuid AS uuid,
                           connected.name AS name,
                           connected.summary AS summary
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                    params
                )

                result.list { record ->
                    mapOf(
                        "uuid" to record.get("uuid").asString(),
                        "name" to record.get("name").asString(),
                        "summary" to record.get("summary").asString("")
                    )
                }
            }
        } catch (e: Exception) {
            log.warn(e) { "Graph traversal failed: ${e.message}" }
            emptyList()
        }
    }

    private suspend fun vectorSimilaritySearch(
        query: String,
        limit: Int,
        useIndex: Boolean,
        minScore: Double = 0.5
    ): List<Map<String, Any>> {
        if (neo4jDriver == null) return emptyList()

        val queryEmbedding = withContext(Dispatchers.IO) {
            embeddingModel.embed(query).toList()
        }

        return if (useIndex) {
            vectorSimilaritySearchIndexed(queryEmbedding, limit, minScore)
        } else {
            vectorSimilaritySearchExhaustive(queryEmbedding, limit, minScore)
        }
    }

    private fun vectorSimilaritySearchExhaustive(
        queryEmbedding: List<Float>,
        limit: Int,
        minScore: Double
    ): List<Map<String, Any>> {
        if (neo4jDriver == null) return emptyList()

        return try {
            neo4jDriver.session().use { session ->
                val result = session.run(
                    """
                    MATCH (n:MemoryObject)
                    WHERE n.group_id = ${'$'}groupId
                      AND n.embedding IS NOT NULL
                    WITH n, vector.similarity.cosine(n.embedding, ${'$'}queryEmbedding) AS score
                    WHERE score > ${'$'}minScore
                    RETURN n.uuid AS uuid,
                           n.name AS name,
                           n.summary AS summary,
                           score
                    ORDER BY score DESC
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                    mapOf(
                        "queryEmbedding" to queryEmbedding,
                        "groupId" to groupId,
                        "limit" to limit,
                        "minScore" to minScore
                    )
                )

                result.list { record ->
                    mapOf(
                        "uuid" to record.get("uuid").asString(),
                        "name" to record.get("name").asString(),
                        "summary" to record.get("summary").asString(""),
                        "score" to record.get("score").asDouble()
                    )
                }
            }
        } catch (e: Exception) {
            log.warn(e) { "Vector similarity search (exhaustive) failed: ${e.message}" }
            emptyList()
        }
    }

    private fun vectorSimilaritySearchIndexed(
        queryEmbedding: List<Float>,
        limit: Int,
        minScore: Double
    ): List<Map<String, Any>> {
        if (neo4jDriver == null) return emptyList()

        return try {
            neo4jDriver.session().use { session ->
                val result = session.run(
                    """
                    CALL db.index.vector.queryNodes('memory_object_vector', ${'$'}limit, ${'$'}queryEmbedding)
                    YIELD node, score
                    WHERE node.group_id = ${'$'}groupId
                      AND score > ${'$'}minScore
                    RETURN node.uuid AS uuid,
                           node.name AS name,
                           node.summary AS summary,
                           score
                    ORDER BY score DESC
                    """.trimIndent(),
                    mapOf(
                        "queryEmbedding" to queryEmbedding,
                        "groupId" to groupId,
                        "limit" to limit,
                        "minScore" to minScore
                    )
                )

                result.list { record ->
                    mapOf(
                        "uuid" to record.get("uuid").asString(),
                        "name" to record.get("name").asString(),
                        "summary" to record.get("summary").asString(""),
                        "score" to record.get("score").asDouble()
                    )
                }
            }
        } catch (e: Exception) {
            log.warn(e) { "Vector similarity search (indexed) failed: ${e.message}" }
            emptyList()
        }
    }

    private fun parseInstant(dateStr: String?): Instant? {
        if (dateStr.isNullOrBlank()) return null
        
        return try {
            Instant.parse(dateStr)
        } catch (e: Exception) {
            log.warn { "Failed to parse datetime '$dateStr': ${e.message}" }
            null
        }
    }

    suspend fun initializeIndexes() {
        if (neo4jDriver == null) {
            log.warn { "Neo4j driver is null, cannot initialize indexes" }
            return
        }

        log.info { "Initializing Neo4j indexes..." }

        try {
            neo4jDriver.session().use { session ->
                session.run(
                    """
                    CREATE FULLTEXT INDEX memory_object_index IF NOT EXISTS
                    FOR (n:MemoryObject) ON EACH [n.name]
                    """.trimIndent()
                )
            }
            log.info { "Fulltext index 'memory_object_index' created successfully" }
        } catch (e: Exception) {
            log.error(e) { "Failed to create fulltext index: ${e.message}" }
            throw e
        }

        try {
            createVectorIndex()
        } catch (e: Exception) {
            log.error(e) { "Failed to create vector index: ${e.message}" }
            throw e
        }
    }

    suspend fun createVectorIndex() {
        if (neo4jDriver == null) {
            log.warn { "Neo4j driver is null, cannot create vector index" }
            return
        }

        log.info { "Creating vector index 'memory_object_vector' for Entity name embeddings..." }

        try {
            neo4jDriver.session().use { session ->
                session.run(
                    """
                    CREATE VECTOR INDEX memory_object_vector IF NOT EXISTS
                    FOR (n:MemoryObject) ON (n.embedding)
                    OPTIONS {
                        indexConfig: {
                            `vector.dimensions`: 3072,
                            `vector.similarity_function`: 'cosine',
                            `vector.hnsw.m`: 32,
                            `vector.hnsw.ef_construction`: 200
                        }
                    }
                    """.trimIndent()
                )
            }
            log.info { "Vector index 'memory_object_vector' created successfully (M=32, ef_construction=200)" }
        } catch (e: Exception) {
            log.error(e) { "Failed to create vector index: ${e.message}" }
            throw e
        }
    }

    /**
     * Add a fact directly to the knowledge graph without LLM extraction.
     * Creates or finds entities and establishes relationship between them.
     */
    suspend fun addFactDirectly(
        from: String,
        relation: String,
        to: String,
        summary: String? = null
    ): String {
        if (neo4jDriver == null) {
            throw IllegalStateException("Neo4j driver is not initialized")
        }

        val referenceTime = Clock.System.now()

        log.info { "Adding fact directly: $from -[$relation]-> $to" }

        // Generate embeddings for entities
        val fromEmbedding = embeddingModel.embed(from)
        val toEmbedding = embeddingModel.embed(to)
        val relationEmbedding = embeddingModel.embed(relation)

        // Create entity nodes
        val fromUuid = UUID.randomUUID().toString()
        val toUuid = UUID.randomUUID().toString()
        val edgeUuid = UUID.randomUUID().toString()

        // Determine entity type (default to "Entity" for now)
        val fromType = "Entity"
        val toType = "Entity"

        // Use provided summary or generate default
        val fromSummary = summary ?: ""
        val toSummary = ""

        neo4jDriver.session().use { session ->
            // Create or merge FROM entity
            val fromResult = session.run(
                """
                MERGE (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
                ON CREATE SET
                    n.uuid = ${'$'}uuid,
                    n.embedding = ${'$'}embedding,
                    n.summary = ${'$'}summary,
                    n.labels = ${'$'}labels,
                    n.created_at = datetime(${'$'}createdAt)
                ON MATCH SET
                    n.embedding = ${'$'}embedding,
                    n.summary = CASE WHEN ${'$'}summary <> '' THEN ${'$'}summary ELSE n.summary END
                RETURN n.uuid as uuid
                """.trimIndent(),
                Values.parameters(
                    "name", from,
                    "groupId", groupId,
                    "uuid", fromUuid,
                    "embedding", fromEmbedding.toList(),
                    "summary", fromSummary,
                    "labels", listOf(fromType),
                    "createdAt", referenceTime.toString()
                )
            )
            if (fromResult.hasNext()) {
                val actualUuid = fromResult.next()["uuid"].asString()
                log.debug { "FROM entity: $from (uuid: $actualUuid)" }
            }

            // Create or merge TO entity
            val toResult = session.run(
                """
                MERGE (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
                ON CREATE SET
                    n.uuid = ${'$'}uuid,
                    n.embedding = ${'$'}embedding,
                    n.summary = ${'$'}summary,
                    n.labels = ${'$'}labels,
                    n.created_at = datetime(${'$'}createdAt)
                ON MATCH SET
                    n.embedding = ${'$'}embedding
                RETURN n.uuid as uuid
                """.trimIndent(),
                Values.parameters(
                    "name", to,
                    "groupId", groupId,
                    "uuid", toUuid,
                    "embedding", toEmbedding.toList(),
                    "summary", toSummary,
                    "labels", listOf(toType),
                    "createdAt", referenceTime.toString()
                )
            )
            if (toResult.hasNext()) {
                val actualUuid = toResult.next()["uuid"].asString()
                log.debug { "TO entity: $to (uuid: $actualUuid)" }
            }

            // Create relationship
            val edgeResult = session.run(
                """
                MATCH (source:MemoryObject {name: ${'$'}fromName, group_id: ${'$'}groupId})
                MATCH (target:MemoryObject {name: ${'$'}toName, group_id: ${'$'}groupId})
                MERGE (source)-[r:LINKS_TO {
                    uuid: ${'$'}edgeUuid,
                    group_id: ${'$'}groupId
                }]->(target)
                ON CREATE SET
                    r.description = ${'$'}description,
                    r.description_embedding = ${'$'}embedding,
                    r.episodes = [],
                    r.valid_at = datetime(${'$'}validAt),
                    r.invalid_at = null,
                    r.created_at = datetime(${'$'}createdAt)
                RETURN r.uuid as uuid
                """.trimIndent(),
                Values.parameters(
                    "fromName", from,
                    "toName", to,
                    "groupId", groupId,
                    "edgeUuid", edgeUuid,
                    "description", relation,
                    "embedding", relationEmbedding.toList(),
                    "validAt", referenceTime.toString(),
                    "createdAt", referenceTime.toString()
                )
            )
            if (edgeResult.hasNext()) {
                val actualUuid = edgeResult.next()["uuid"].asString()
                log.debug { "Created relationship: $actualUuid" }
            }
        }

        return "Successfully added fact: '$from' -[$relation]-> '$to'"
    }

    /**
     * Get detailed information about an entity including all its relationships.
     */
    suspend fun getEntityDetails(name: String): String {
        if (neo4jDriver == null) {
            throw IllegalStateException("Neo4j driver is not initialized")
        }

        log.info { "Getting entity details for: $name" }

        return neo4jDriver.session().use { session ->
            // Get entity info
            val entityResult = session.run(
                """
                MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
                RETURN n.uuid as uuid, n.summary as summary, n.labels as labels, n.created_at as createdAt
                """.trimIndent(),
                Values.parameters("name", name, "groupId", groupId)
            )

            if (!entityResult.hasNext()) {
                return@use "Entity '$name' not found in knowledge graph"
            }

            val entity = entityResult.next()
            val uuid = entity["uuid"].asString()
            val summary = entity["summary"].asString()
            val labels = entity["labels"].asList { it.asString() }
            val createdAt = entity["createdAt"].asZonedDateTime().toString()

            // Get outgoing relationships
            val outgoingResult = session.run(
                """
                MATCH (n:MemoryObject {uuid: ${'$'}uuid})-[r:LINKS_TO]->(target:MemoryObject)
                WHERE r.invalid_at IS NULL
                RETURN r.description as relation, target.name as targetName
                """.trimIndent(),
                Values.parameters("uuid", uuid)
            )

            val outgoing = mutableListOf<String>()
            while (outgoingResult.hasNext()) {
                val record = outgoingResult.next()
                outgoing.add("${record["relation"].asString()} -> ${record["targetName"].asString()}")
            }

            // Get incoming relationships
            val incomingResult = session.run(
                """
                MATCH (source:MemoryObject)-[r:LINKS_TO]->(n:MemoryObject {uuid: ${'$'}uuid})
                WHERE r.invalid_at IS NULL
                RETURN source.name as sourceName, r.description as relation
                """.trimIndent(),
                Values.parameters("uuid", uuid)
            )

            val incoming = mutableListOf<String>()
            while (incomingResult.hasNext()) {
                val record = incomingResult.next()
                incoming.add("${record["sourceName"].asString()} -> ${record["relation"].asString()}")
            }

            buildString {
                appendLine("Entity: $name")
                appendLine("Type: ${labels.firstOrNull() ?: "Unknown"}")
                appendLine("Summary: $summary")
                appendLine("Created: $createdAt")
                appendLine()
                if (outgoing.isNotEmpty()) {
                    appendLine("Outgoing relationships:")
                    outgoing.forEach { appendLine("  - $it") }
                    appendLine()
                }
                if (incoming.isNotEmpty()) {
                    appendLine("Incoming relationships:")
                    incoming.forEach { appendLine("  - $it") }
                }
                if (outgoing.isEmpty() && incoming.isEmpty()) {
                    appendLine("No relationships found")
                }
            }
        }
    }

    /**
     * Invalidate a fact by setting its invalid_at timestamp (bi-temporal model).
     * The fact is not deleted - it remains in the database for historical queries.
     */
    suspend fun invalidateFact(
        from: String,
        relation: String,
        to: String
    ): String {
        if (neo4jDriver == null) {
            throw IllegalStateException("Neo4j driver is not initialized")
        }

        val invalidAt = Clock.System.now()

        log.info { "Invalidating fact: $from -[$relation]-> $to" }

        return neo4jDriver.session().use { session ->
            val result = session.run(
                """
                MATCH (source:MemoryObject {name: ${'$'}fromName, group_id: ${'$'}groupId})
                      -[r:LINKS_TO {description: ${'$'}description}]->
                      (target:MemoryObject {name: ${'$'}toName, group_id: ${'$'}groupId})
                WHERE r.invalid_at IS NULL
                SET r.invalid_at = datetime(${'$'}invalidAt)
                RETURN count(r) as count
                """.trimIndent(),
                Values.parameters(
                    "fromName", from,
                    "toName", to,
                    "description", relation,
                    "groupId", groupId,
                    "invalidAt", invalidAt.toString()
                )
            )

            if (result.hasNext()) {
                val count = result.next()["count"].asInt()
                if (count > 0) {
                    log.debug { "Invalidated $count relationship(s)" }
                    "Successfully invalidated fact: '$from' -[$relation]-> '$to' (marked as invalid at $invalidAt)"
                } else {
                    "No matching fact found to invalidate: '$from' -[$relation]-> '$to'"
                }
            } else {
                "No matching fact found to invalidate: '$from' -[$relation]-> '$to'"
            }
        }
    }

    /**
     * Update an existing entity's summary or type.
     */
    suspend fun updateEntity(
        name: String,
        newSummary: String? = null,
        newType: String? = null
    ): String {
        if (neo4jDriver == null) {
            throw IllegalStateException("Neo4j driver is not initialized")
        }

        log.info { "Updating entity: $name (summary: ${newSummary != null}, type: ${newType != null})" }

        return neo4jDriver.session().use { session ->
            // Build dynamic SET clause
            val setClauses = mutableListOf<String>()
            val params = mutableMapOf<String, Any>(
                "name" to name,
                "groupId" to groupId
            )

            if (newSummary != null) {
                setClauses.add("n.summary = \$newSummary")
                params["newSummary"] = newSummary
            }

            if (newType != null) {
                setClauses.add("n.labels = \$newLabels")
                params["newLabels"] = listOf(newType)
            }

            if (setClauses.isEmpty()) {
                return@use "No updates specified for entity '$name'"
            }

            val result = session.run(
                """
                MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
                SET ${setClauses.joinToString(", ")}
                RETURN count(n) as count
                """.trimIndent(),
                Values.parameters(params)
            )

            if (result.hasNext()) {
                val count = result.next()["count"].asInt()
                if (count > 0) {
                    val updates = mutableListOf<String>()
                    if (newSummary != null) updates.add("summary")
                    if (newType != null) updates.add("type")

                    "Successfully updated entity '$name' (${updates.joinToString(", ")})"
                } else {
                    "Entity '$name' not found in knowledge graph"
                }
            } else {
                "Entity '$name' not found in knowledge graph"
            }
        }
    }

    /**
     * ⚠️ DANGER: Permanently delete an entity and optionally its relationships.
     * This is a HARD DELETE with NO UNDO - use with extreme caution!
     * For soft delete with history preservation, use invalidateFact() instead.
     */
    suspend fun hardDeleteEntity(
        name: String,
        cascade: Boolean = true
    ): String {
        if (neo4jDriver == null) {
            throw IllegalStateException("Neo4j driver is not initialized")
        }

        log.warn { "⚠️ HARD DELETE requested for entity: $name (cascade: $cascade)" }

        return neo4jDriver.session().use { session ->
            // First, count what will be deleted
            val countResult = session.run(
                """
                MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
                OPTIONAL MATCH (n)-[r:LINKS_TO]-()
                RETURN count(DISTINCT n) as nodeCount, count(DISTINCT r) as edgeCount
                """.trimIndent(),
                Values.parameters("name", name, "groupId", groupId)
            )

            if (!countResult.hasNext()) {
                return@use "Entity '$name' not found in knowledge graph"
            }

            val counts = countResult.next()
            val nodeCount = counts["nodeCount"].asInt()
            val edgeCount = counts["edgeCount"].asInt()

            if (nodeCount == 0) {
                return@use "Entity '$name' not found in knowledge graph"
            }

            // Delete relationships if cascade is true
            if (cascade && edgeCount > 0) {
                session.run(
                    """
                    MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
                    MATCH (n)-[r:LINKS_TO]-()
                    DELETE r
                    """.trimIndent(),
                    Values.parameters("name", name, "groupId", groupId)
                )
                log.warn { "Deleted $edgeCount relationship(s) for entity: $name" }
            }

            // Delete the entity node
            session.run(
                """
                MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
                DELETE n
                """.trimIndent(),
                Values.parameters("name", name, "groupId", groupId)
            )

            log.warn { "⚠️ HARD DELETED entity: $name" }

            buildString {
                appendLine("⚠️ PERMANENTLY DELETED entity '$name':")
                appendLine("- Nodes deleted: $nodeCount")
                if (cascade) {
                    appendLine("- Relationships deleted: $edgeCount")
                } else {
                    appendLine("- Relationships preserved: $edgeCount")
                }
                appendLine()
                appendLine("This operation CANNOT be undone!")
            }
        }
    }
}
