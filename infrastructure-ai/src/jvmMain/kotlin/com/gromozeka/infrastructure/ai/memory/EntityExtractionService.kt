package com.gromozeka.infrastructure.ai.memory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.gromozeka.infrastructure.ai.springai.ChatModelFactory
import com.gromozeka.domain.model.memory.EntityType
import com.gromozeka.domain.model.memory.EntityTypesConfig
import com.gromozeka.infrastructure.ai.memory.models.MissedEntities
import com.gromozeka.domain.model.AIProvider
import klog.KLoggers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.serialization.json.Json
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class EntityExtractionService(
    private val chatModelFactory: ChatModelFactory,
    @Value("\${gromozeka.ai.provider:CLAUDE_CODE}")
    private val aiProvider: String,
    @Value("\${gromozeka.ai.gemini.model:gemini-2.0-flash-thinking-exp-01-21}")
    private val geminiModel: String,
    @Value("\${gromozeka.ai.claude.model:claude-sonnet-4-5}")
    private val claudeModel: String,
    @Value("\${gromozeka.ai.ollama.model:llama3}")
    private val ollamaModel: String
) {
    private val log = KLoggers.logger(this)
    private val objectMapper = ObjectMapper()
    val entityTypes: List<EntityType> = run {
        val configJson = this::class.java.classLoader
            .getResourceAsStream("memory/entity-types.json")
            ?.bufferedReader()
            ?.readText()
            ?: throw IllegalStateException("entity-types.json not found")

        Json.decodeFromString<EntityTypesConfig>(configJson).entityTypes
    }

    companion object {
        private const val MAX_SUMMARY_CHARS = 250
        private const val MAX_REFLEXION_ITERATIONS = 2
    }

    private fun getChatModel() = chatModelFactory.get(
        provider = AIProvider.valueOf(aiProvider),
        modelName = when (AIProvider.valueOf(aiProvider)) {
            AIProvider.GEMINI -> geminiModel
            AIProvider.CLAUDE_CODE -> claudeModel
            AIProvider.OLLAMA -> ollamaModel
            AIProvider.OPEN_AI -> TODO()
        },
        projectPath = null
    )

    suspend fun extractEntities(
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

            val chatModel = getChatModel()

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

    suspend fun checkMissedEntities(
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

        val chatModel = getChatModel()

        val response = chatModel.stream(Prompt(UserMessage(prompt))).collectList().awaitSingle().lastOrNull()
            ?.result?.output?.text
            ?: return emptyList()

        log.debug { "Reflexion LLM response: ${response.take(200)}..." }

        return parseMissedEntitiesResponse(response)
    }

    fun parseMissedEntitiesResponse(response: String): List<String> {
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

    suspend fun generateEntitySummary(
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

        val chatModel = getChatModel()

        val response = chatModel.stream(Prompt(UserMessage(prompt))).collectList().awaitSingle().lastOrNull()
            ?.result?.output?.text

        val summary = response?.trim()?.let { truncateAtSentence(it, MAX_SUMMARY_CHARS) } ?: ""
        log.debug { "Generated summary for $entityName: $summary" }

        return summary
    }

    fun parseEntitiesResponse(response: String): List<Pair<String, Int>> {
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

    private fun truncateAtSentence(text: String, maxChars: Int): String {
        if (text.length <= maxChars) {
            return text
        }

        val truncated = text.substring(0, maxChars)

        val sentencePattern = Regex("[.!?](?:\\s|$)")
        val matches = sentencePattern.findAll(truncated).toList()

        return if (matches.isNotEmpty()) {
            val lastMatch = matches.last()
            text.substring(0, lastMatch.range.last + 1).trim()
        } else {
            truncated.trim()
        }
    }
}
