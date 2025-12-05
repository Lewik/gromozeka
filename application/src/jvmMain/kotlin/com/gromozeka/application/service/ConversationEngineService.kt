package com.gromozeka.application.service

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.repository.AgentDomainService
import com.gromozeka.domain.repository.ConversationDomainService
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.service.ChatModelProvider
import com.gromozeka.domain.service.McpToolProvider
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.stereotype.Service
import java.util.*

/**
 * LLM-agnostic service for managing conversation with AI using User-Controlled Tool Execution.
 *
 * Uses blocking call() instead of streaming for better provider compatibility.
 * Tool execution loop:
 * 1. Call ChatModel with internalToolExecutionEnabled=false
 * 2. Check if the response contains tool calls
 * 3. Execute tool calls in parallel
 * 4. Send tool results back to the model
 * 5. Repeat until no more tool calls
 */
@Service
class ConversationEngineService(
    private val chatModelProvider: ChatModelProvider,
    private val agentDomainService: AgentDomainService,
    private val toolApprovalService: ToolApprovalService,
    private val parallelToolExecutor: ParallelToolExecutor,
    private val conversationService: ConversationDomainService,
    private val threadRepository: ThreadRepository,
    private val threadMessageRepository: com.gromozeka.domain.repository.ThreadMessageRepository,
    private val messageConversionService: MessageConversionService,
    private val toolCallbacks: List<org.springframework.ai.tool.ToolCallback>,
    private val mcpToolProvider: McpToolProvider,
    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
    private val coroutineScope: CoroutineScope,
    private val vectorMemoryService: com.gromozeka.domain.service.VectorMemoryService,
    private val knowledgeGraphService: com.gromozeka.domain.service.KnowledgeGraphService?,
) {
    private val log = KLoggers.logger(this)

    /**
     * Collect all available tools for user-controlled tool execution.
     * Tools are passed in runtime options to ToolCallingManager.
     */
    private fun collectToolOptions(projectPath: String?): ChatOptions {
        val allCallbacks = mutableListOf<org.springframework.ai.tool.ToolCallback>()
        val allNames = mutableSetOf<String>()

        // Built-in @Bean tools
        allCallbacks.addAll(toolCallbacks)
        allNames.addAll(toolCallbacks.map { it.toolDefinition.name() })
        log.debug { "Built-in @Bean tools: ${toolCallbacks.size}" }

        // MCP tools
        val mcpCallbacks = mcpToolProvider.getToolCallbacks()
        allCallbacks.addAll(mcpCallbacks)
        allNames.addAll(mcpCallbacks.map { it.toolDefinition.name() })
        log.debug { "MCP tools: ${mcpCallbacks.size}" }

        log.debug { "Total tools for runtime options: ${allCallbacks.size}" }

        return ToolCallingChatOptions.builder()
            .toolCallbacks(allCallbacks)
            .toolNames(allNames)
            .internalToolExecutionEnabled(false)
            .toolContext(mapOf("projectPath" to projectPath))
            .build()
    }

    /**
     * Send message and get response using blocking call().
     * LLM-agnostic - works with all providers (Anthropic, OpenAI, Google, etc.)
     *
     * @param conversationId The conversation to append messages to
     * @param userMessage The user message to send
     * @param agent The agent to use for this conversation (provides system prompts)
     * @return Flow of Message objects (assistant responses, tool results).
     *         Flow completes when conversation turn is done.
     */
    suspend fun sendMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: Agent,
    ): Flow<Conversation.Message> = flow {
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val projectPath = conversationService.getProjectPath(conversationId)
        val chatModel = chatModelProvider.getChatModel(
            AIProvider.valueOf(conversation.aiProvider),
            conversation.modelName,
            projectPath
        )
        val messages = conversationService.loadCurrentMessages(conversationId)

        conversationService.addMessage(conversationId, userMessage)

        val systemMessages = agentDomainService
            .assembleSystemPrompt(agent, projectPath)
            .map { SystemMessage(it) }

        val springHistory = messageConversionService.convertHistoryToSpringAI(messages + userMessage)
        val toolOptions = collectToolOptions(projectPath)

        var currentPrompt = Prompt(systemMessages + springHistory, toolOptions)
        var iterationCount = 0
        val maxIterations = 200

        while (iterationCount < maxIterations) {
            iterationCount++

            if (iterationCount > 1) {
                val currentMessages = conversationService.loadCurrentMessages(conversationId)
                val currentSpringHistory = messageConversionService.convertHistoryToSpringAI(currentMessages)
                currentPrompt = Prompt(systemMessages + currentSpringHistory, toolOptions)
            }

            val chatResponse: ChatResponse
            try {
                chatResponse = withContext(Dispatchers.IO) {
                    chatModel.call(currentPrompt)
                }
            } catch (e: Exception) {
                log.error(e) { "Chat call error" }
                val errorMessage = createErrorMessage(conversationId, e.message ?: "Unknown error")
                emit(errorMessage)
                conversationService.addMessage(conversationId, errorMessage)
                break
            }

            chatResponse.metadata.usage?.let { usage ->
                val turnNumber = threadRepository.incrementTurnNumber(conversation.currentThread)
                try {
                    val nativeUsage = usage.getNativeUsage()

                    val (cacheCreation, cacheRead) = when (nativeUsage) {
                        is AnthropicApi.Usage -> Pair(
                            nativeUsage.cacheCreationInputTokens() ?: 0,
                            nativeUsage.cacheReadInputTokens() ?: 0
                        )
                        else -> Pair(0, 0)
                    }

                    val thinkingTokens = when (usage) {
                        is GoogleGenAiUsage -> usage.thoughtsTokenCount ?: 0
                        else -> 0
                    }

                    tokenUsageStatisticsRepository.save(
                        TokenUsageStatistics(
                            id = TokenUsageStatistics.Id(UUID.randomUUID().toString()),
                            threadId = conversation.currentThread,
                            turnNumber = turnNumber,
                            timestamp = Clock.System.now(),
                            promptTokens = usage.promptTokens ?: 0,
                            completionTokens = usage.completionTokens ?: 0,
                            cacheCreationTokens = cacheCreation,
                            cacheReadTokens = cacheRead,
                            thinkingTokens = thinkingTokens,
                            provider = conversation.aiProvider,
                            modelId = conversation.modelName
                        )
                    )
                } catch (e: Exception) {
                    log.error(e) { "Failed to save token usage statistics for turn $turnNumber" }
                }
            }

            val generation = chatResponse.results.firstOrNull()
            if (generation == null) {
                log.warn { "Empty response from chat model" }
                break
            }

            val allToolCalls = chatResponse.results.flatMap { it.output.toolCalls }
            val hasToolCalls = chatResponse.hasToolCalls()

            val assistantMessage = createAssistantMessageFromResponse(conversationId, chatResponse)
            emit(assistantMessage)
            conversationService.addMessage(conversationId, assistantMessage)

            if (hasToolCalls) {
                val approvalResult = toolApprovalService.approve(allToolCalls)
                if (approvalResult is ApprovalResult.Rejected) {
                    log.warn { "Tool calls rejected: ${approvalResult.reason}" }
                    val rejectedMessage = assistantMessage.copy(
                        error = Conversation.Message.GenerationError(
                            message = "Tool calls rejected: ${approvalResult.reason}",
                            type = "rejected"
                        )
                    )
                    emit(rejectedMessage)
                    break
                }

                val toolContext = ToolContext(
                    mapOf("projectPath" to projectPath)
                )
                val executionResult = parallelToolExecutor.executeParallel(
                    toolCalls = allToolCalls,
                    toolContext = toolContext,
                    scope = coroutineScope
                )

                val toolResultMessage = Conversation.Message(
                    id = Conversation.Message.Id(UUID.randomUUID().toString()),
                    conversationId = conversationId,
                    role = Conversation.Message.Role.USER,
                    content = executionResult.results,
                    createdAt = Clock.System.now()
                )
                emit(toolResultMessage)
                conversationService.addMessage(conversationId, toolResultMessage)

                if (executionResult.returnDirect) {
                    break
                }
            } else {
                break
            }
        }

        if (iterationCount >= maxIterations) {
            val errorMessage = createErrorMessage(
                conversationId,
                "Tool execution loop exceeded maximum iterations ($maxIterations)",
                "max_iterations"
            )
            emit(errorMessage)
        }
    }

    private fun createAssistantMessageFromResponse(
        conversationId: Conversation.Id,
        chatResponse: ChatResponse,
    ): Conversation.Message {
        val content = mutableListOf<ContentItem>()

        chatResponse.results.forEach { generation ->
            val assistantMsg = generation.output

            if (!assistantMsg.text.isNullOrBlank()) {
                content.add(
                    ContentItem.AssistantMessage(
                        structured = Conversation.Message.StructuredText(fullText = assistantMsg.text ?: ""),
                        state = BlockState.COMPLETE
                    )
                )
            }

            assistantMsg.toolCalls.forEach { toolCall ->
                val input = try {
                    Json.parseToJsonElement(toolCall.arguments())
                } catch (e: Exception) {
                    log.error(e) { "Failed to parse tool call arguments for ${toolCall.name()}: ${toolCall.arguments()}" }
                    JsonObject(
                        mapOf(
                            "error" to kotlinx.serialization.json.JsonPrimitive("Parse error: ${e.message}"),
                            "raw" to kotlinx.serialization.json.JsonPrimitive(toolCall.arguments())
                        )
                    )
                }
                content.add(
                    ContentItem.ToolCall(
                        id = ContentItem.ToolCall.Id(toolCall.id()),
                        call = ContentItem.ToolCall.Data(
                            name = toolCall.name(),
                            input = input
                        ),
                        state = BlockState.COMPLETE
                    )
                )
            }
        }

        return Conversation.Message(
            id = Conversation.Message.Id(UUID.randomUUID().toString()),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = content,
            createdAt = Clock.System.now()
        )
    }

    private fun createErrorMessage(
        conversationId: Conversation.Id,
        message: String,
        type: String = "error",
    ): Conversation.Message {
        return Conversation.Message(
            id = Conversation.Message.Id(UUID.randomUUID().toString()),
            conversationId = conversationId,
            role = Conversation.Message.Role.SYSTEM,
            content = listOf(
                ContentItem.System(
                    level = ContentItem.System.SystemLevel.ERROR,
                    content = message,
                    state = BlockState.COMPLETE
                )
            ),
            createdAt = Clock.System.now(),
            error = Conversation.Message.GenerationError(message = message, type = type)
        )
    }

    /**
     * Remember current thread messages to vector memory.
     * This method triggers incremental embedding of thread messages for semantic recall.
     */
    suspend fun rememberCurrentThread(conversationId: Conversation.Id) {
        try {
            val conversation = conversationService.findById(conversationId)
                ?: throw IllegalStateException("Conversation not found: $conversationId")

            vectorMemoryService.rememberThread(conversation.currentThread.value)
            log.info { "Remembered thread ${conversation.currentThread} for conversation $conversationId" }
        } catch (e: Exception) {
            log.error(e) { "Failed to remember thread for conversation $conversationId: ${e.message}" }
            throw e
        }
    }

    /**
     * Add current thread to knowledge graph (Phase 2).
     * This method triggers entity and relationship extraction from conversation.
     */
    suspend fun addToGraphCurrentThread(conversationId: Conversation.Id) {
        if (knowledgeGraphService == null) {
            log.warn { "KnowledgeGraphService not available (knowledge-graph.enabled=false)" }
            return
        }

        try {
            val conversation = conversationService.findById(conversationId)
                ?: throw IllegalStateException("Conversation not found: $conversationId")

            val threadMessages = threadMessageRepository.getMessagesByThread(conversation.currentThread)
                .filter { message ->
                    message.role in listOf(Conversation.Message.Role.USER, Conversation.Message.Role.ASSISTANT)
                }
                .joinToString("\n\n") { message ->
                    "${message.role}: ${extractGraphTextContent(message)}"
                }

            log.info { "Adding thread ${conversation.currentThread} to knowledge graph for conversation $conversationId" }

            val result = knowledgeGraphService.extractAndSaveToGraph(threadMessages)
            log.info { "Knowledge graph update result: $result" }
        } catch (e: Exception) {
            log.error(e) { "Failed to add thread to graph for conversation $conversationId: ${e.message}" }
            throw e
        }
    }

    private fun extractGraphTextContent(message: Conversation.Message): String {
        return message.content.mapNotNull { contentItem ->
            when (contentItem) {
                is ContentItem.UserMessage -> contentItem.text
                is ContentItem.AssistantMessage -> contentItem.structured.fullText
                else -> null
            }
        }.joinToString("\\n")
    }
}
