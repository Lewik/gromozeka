package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredProjectId
import com.gromozeka.domain.tool.requiredWorkerId
import com.gromozeka.shared.utils.sha256
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import kotlin.math.ln

const val SEARCH_TOOLS_TOOL_NAME = "search_tools"
const val SEARCH_TOOLS_DEFAULT_LIMIT = 8
const val SEARCH_TOOLS_MAX_LIMIT = 20

data class AiToolSearchMatch(
    val tool: AiToolCallback,
    val score: Double,
)

@Service
class AiToolSearchService {
    private val json = Json { ignoreUnknownKeys = true }
    private val indexCache = object : LinkedHashMap<String, InMemoryBm25Index<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, InMemoryBm25Index<String>>,
        ): Boolean = size > MAX_CACHED_INDEXES
    }

    fun search(
        tools: List<AiToolCallback>,
        query: String,
        limit: Int = SEARCH_TOOLS_DEFAULT_LIMIT,
    ): List<AiToolSearchMatch> {
        require(query.isNotBlank()) { "Tool search query must not be blank" }
        require(limit in 1..SEARCH_TOOLS_MAX_LIMIT) {
            "Tool search limit must be between 1 and $SEARCH_TOOLS_MAX_LIMIT"
        }

        val searchableTools = tools
            .filterNot { it.definition.name == SEARCH_TOOLS_TOOL_NAME }
            .associateBy { it.definition.name }
            .toSortedMap()
        val index = indexFor(searchableTools.values.map(AiToolCallback::definition))
        val exactName = normalizeToolName(query)

        return index.search(query, searchableTools.size)
            .mapNotNull { result ->
                searchableTools[result.id]?.let { tool ->
                    AiToolSearchMatch(tool = tool, score = result.score)
                }
            }
            .sortedWith(
                compareByDescending<AiToolSearchMatch> {
                    normalizeToolName(it.tool.definition.name) == exactName
                }.thenByDescending(AiToolSearchMatch::score)
                    .thenBy { it.tool.definition.name }
            )
            .take(limit)
    }

    @Synchronized
    private fun indexFor(definitions: List<AiToolDefinition>): InMemoryBm25Index<String> {
        val signature = definitions.joinToString(separator = "\u0000") { definition ->
            listOf(
                definition.name,
                definition.description,
                definition.inputSchema,
                definition.source,
            ).joinToString(separator = "\u0001")
        }.sha256()

        return indexCache.getOrPut(signature) {
            InMemoryBm25Index(
                definitions.map { definition ->
                    Bm25Document(
                        id = definition.name,
                        text = definition.toSearchText(),
                    )
                }
            )
        }
    }

    private fun AiToolDefinition.toSearchText(): String = buildList {
        add(name)
        add(name.replace('_', ' '))
        add(source)
        add(description)
        runCatching { json.parseToJsonElement(inputSchema) }
            .getOrNull()
            ?.let { schema -> appendSchemaSearchText(schema) }
    }.joinToString(" ")

    private fun MutableList<String>.appendSchemaSearchText(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                element["description"]
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
                element["properties"]
                    ?.let { it as? JsonObject }
                    ?.forEach { (name, schema) ->
                        add(name)
                        appendSchemaSearchText(schema)
                    }
                listOf("items", "anyOf", "oneOf", "allOf").forEach { key ->
                    element[key]?.let { nested -> appendSchemaSearchText(nested) }
                }
            }

            is JsonArray -> element.forEach { nested -> appendSchemaSearchText(nested) }
            else -> Unit
        }
    }

    private companion object {
        const val MAX_CACHED_INDEXES = 16
    }
}

@Service
class AiToolRuntimeCatalogService {
    private val json = Json { ignoreUnknownKeys = true }

    fun availableTools(
        agent: AgentDefinition,
        catalog: DistributedAiToolCatalogSnapshot,
        messages: List<Conversation.Message>,
    ): List<AiToolCallback> {
        val toolsByName = catalog.tools.associateBy { it.definition.name }
        require(SEARCH_TOOLS_TOOL_NAME in toolsByName) {
            "Current conversation runtime does not provide required tool '$SEARCH_TOOLS_TOOL_NAME'"
        }

        val missingPinnedTools = agent.tools.filterNot(toolsByName::containsKey)
        require(missingPinnedTools.isEmpty()) {
            "Agent ${agent.id.value} pins unavailable tools: ${missingPinnedTools.sorted().joinToString()}"
        }

        return loadedToolNames(agent, messages)
            .mapNotNull(toolsByName::get)
            .sortedBy { it.definition.name }
    }

    internal fun loadedToolNames(
        agent: AgentDefinition,
        messages: List<Conversation.Message>,
    ): Set<String> = buildSet {
        add(SEARCH_TOOLS_TOOL_NAME)
        addAll(agent.tools)
        addAll(discoveredToolNames(messages))
    }

    internal fun discoveredToolNames(messages: List<Conversation.Message>): Set<String> {
        val activeMessages = messages.afterLastCompaction()
        val searchCallIds = activeMessages
            .flatMap(Conversation.Message::content)
            .filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
            .filter { it.call.name == SEARCH_TOOLS_TOOL_NAME }
            .mapTo(mutableSetOf()) { it.id }

        return activeMessages
            .flatMap(Conversation.Message::content)
            .filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
            .filter {
                !it.isError &&
                    it.toolName == SEARCH_TOOLS_TOOL_NAME &&
                    it.toolUseId in searchCallIds
            }
            .flatMap { result ->
                result.result
                    .filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.Text>()
                    .flatMap { text -> parseToolNames(text.content) }
            }
            .toSet()
    }

    private fun List<Conversation.Message>.afterLastCompaction(): List<Conversation.Message> {
        val compactionIndex = indexOfLast { message ->
            message.content.any { it is Conversation.Message.ContentItem.ContextCompactionResult }
        }
        return if (compactionIndex < 0) this else drop(compactionIndex + 1)
    }

    private fun parseToolNames(result: String): List<String> = runCatching {
        json.parseToJsonElement(result)
            .jsonObject["tools"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { tool ->
                tool.jsonObject["name"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
            }
    }.getOrDefault(emptyList())
}

@Component
class SearchToolsToolCallback(
    private val projectDomainService: ProjectDomainService,
    private val agentDomainService: AgentDomainService,
    private val conversationDomainService: ConversationDomainService,
    private val distributedToolCatalog: DistributedAiToolCatalog,
    private val agentSkillRuntimeCatalogService: AgentSkillRuntimeCatalogService,
    private val runtimeCatalogService: AiToolRuntimeCatalogService,
    private val searchService: AiToolSearchService,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Input(
        val query: String,
        val limit: Int = SEARCH_TOOLS_DEFAULT_LIMIT,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = SEARCH_TOOLS_TOOL_NAME,
        description = """
            Search tools that are not currently loaded. Use a short capability-oriented query or an exact tool name.
            Matching tools and their complete input schemas become callable in the next model step. Search again when
            a different capability is needed.
        """.trimIndent(),
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Capability or exact tool name to search for."
                },
                "limit": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": $SEARCH_TOOLS_MAX_LIMIT,
                  "default": $SEARCH_TOOLS_DEFAULT_LIMIT,
                  "description": "Maximum number of matching tools to load."
                }
              },
              "required": ["query"],
              "additionalProperties": false
            }
        """.trimIndent(),
        source = "gromozeka",
    )

    override val metadata: AiToolMetadata = AiToolMetadata(
        executionScope = AiToolExecutionScope.CONVERSATION_RUNTIME,
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = json.decodeFromString<Input>(toolInput)
        require(input.query.isNotBlank()) { "Tool search query must not be blank" }
        require(input.limit in 1..SEARCH_TOOLS_MAX_LIMIT) {
            "Tool search limit must be between 1 and $SEARCH_TOOLS_MAX_LIMIT"
        }

        val projectId = context.requiredProjectId()
        val workerId = context.requiredWorkerId()
        val agentId = context.requiredAgentDefinitionId()
        val conversationId = context.requiredConversationId()
        val project = projectDomainService.findById(projectId)
            ?: error("Project not found: ${projectId.value}")
        val agent = agentDomainService.findById(agentId)
            ?: error("Agent not found: ${agentId.value}")
        require(agent.type is AgentDefinition.Type.Builtin || agent.projectId == project.id) {
            "Agent ${agent.id.value} does not belong to project ${project.id.value}"
        }

        val preparedCatalog = agentSkillRuntimeCatalogService.prepare(
            agent = agent,
            projectId = project.id,
            runtimeWorkerId = workerId,
            toolCatalog = distributedToolCatalog.snapshot(project),
        ).toolCatalog
        val loadedToolNames = runtimeCatalogService.loadedToolNames(
            agent = agent,
            messages = conversationDomainService.loadCurrentMessages(conversationId),
        )
        val matches = searchService.search(
            tools = preparedCatalog.tools.filterNot { it.definition.name in loadedToolNames },
            query = input.query,
            limit = input.limit,
        )

        buildJsonObject {
            put("query", input.query)
            put("catalog_revision", preparedCatalog.environmentRevision)
            put("loaded_for_current_context", true)
            put("count", matches.size)
            putJsonArray("tools") {
                matches.forEach { match ->
                    add(buildJsonObject {
                        put("name", match.tool.definition.name)
                        put("description", match.tool.definition.description)
                        put("source", match.tool.definition.source)
                        put("input_schema", json.parseToJsonElement(match.tool.definition.inputSchema))
                    })
                }
            }
        }.toString()
    }

    private fun ToolExecutionContext?.requiredAgentDefinitionId(): AgentDefinition.Id {
        val value = this?.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)
            ?.takeIf(String::isNotBlank)
            ?: error("Agent definition id is required in tool execution context")
        return AgentDefinition.Id(value)
    }

    private fun ToolExecutionContext?.requiredConversationId(): Conversation.Id {
        val value = this?.getString(TOOL_CONTEXT_CONVERSATION_ID)
            ?.takeIf(String::isNotBlank)
            ?: error("Conversation id is required in tool execution context")
        return Conversation.Id(value)
    }
}

internal data class Bm25Document<T>(
    val id: T,
    val text: String,
)

internal data class Bm25SearchResult<T>(
    val id: T,
    val score: Double,
)

internal class InMemoryBm25Index<T>(
    documents: List<Bm25Document<T>>,
    private val k1: Double = 1.2,
    private val b: Double = 0.75,
) {
    private data class IndexedDocument<T>(
        val id: T,
        val termFrequency: Map<String, Int>,
        val length: Int,
    )

    private val indexedDocuments = documents.map { document ->
        val tokens = tokenize(document.text)
        IndexedDocument(
            id = document.id,
            termFrequency = tokens.groupingBy { it }.eachCount(),
            length = tokens.size,
        )
    }
    private val documentFrequency = indexedDocuments
        .flatMap { it.termFrequency.keys }
        .groupingBy { it }
        .eachCount()
    private val averageDocumentLength = indexedDocuments
        .map(IndexedDocument<T>::length)
        .average()
        .takeUnless(Double::isNaN)
        ?: 0.0

    init {
        require(k1 >= 0.0 && k1.isFinite()) { "BM25 k1 must be finite and non-negative" }
        require(b in 0.0..1.0) { "BM25 b must be between 0 and 1" }
        require(documents.map(Bm25Document<T>::id).distinct().size == documents.size) {
            "BM25 document ids must be unique"
        }
    }

    fun search(query: String, limit: Int): List<Bm25SearchResult<T>> {
        if (limit <= 0 || indexedDocuments.isEmpty()) return emptyList()
        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty()) return emptyList()

        return indexedDocuments
            .mapNotNull { document ->
                val score = queryTerms.sumOf { term -> score(term, document) }
                score.takeIf { it > 0.0 }?.let {
                    Bm25SearchResult(id = document.id, score = score)
                }
            }
            .sortedByDescending(Bm25SearchResult<T>::score)
            .take(limit)
    }

    private fun score(term: String, document: IndexedDocument<T>): Double {
        val termFrequency = document.termFrequency[term] ?: return 0.0
        val documentsWithTerm = documentFrequency[term] ?: return 0.0
        val documentCount = indexedDocuments.size
        val inverseDocumentFrequency = ln(
            1.0 + (documentCount - documentsWithTerm + 0.5) / (documentsWithTerm + 0.5)
        )
        val lengthNormalization = if (averageDocumentLength == 0.0) {
            1.0
        } else {
            1.0 - b + b * document.length / averageDocumentLength
        }
        val saturatedTermFrequency =
            termFrequency * (k1 + 1.0) / (termFrequency + k1 * lengthNormalization)
        return inverseDocumentFrequency * saturatedTermFrequency
    }
}

private fun tokenize(text: String): List<String> {
    val expanded = text.replace(CAMEL_CASE_BOUNDARY, " ")
    return WORD_TOKEN.findAll(expanded)
        .map { it.value.lowercase() }
        .toList()
}

private fun normalizeToolName(value: String): String =
    tokenize(value).joinToString("")

private val CAMEL_CASE_BOUNDARY = Regex("(?<=[\\p{Ll}\\p{Nd}])(?=\\p{Lu})")
private val WORD_TOKEN = Regex("[\\p{L}\\p{N}]+")
