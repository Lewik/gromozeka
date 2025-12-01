//package com.gromozeka.application.service
//
//import com.fasterxml.jackson.databind.ObjectMapper
//import com.gromozeka.domain.model.AIProvider
//import com.gromozeka.domain.model.Agent
//import com.gromozeka.domain.model.Conversation
//import com.gromozeka.domain.repository.AgentDomainService
//import com.gromozeka.domain.repository.ConversationDomainService
//import com.gromozeka.domain.repository.ThreadMessageRepository
//import com.gromozeka.domain.repository.ThreadRepository
//import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
//import com.gromozeka.domain.service.ChatModelProvider
//import com.gromozeka.domain.service.KnowledgeGraphService
//import com.gromozeka.domain.service.VectorMemoryService
//import klog.KLoggers
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.flow
//import org.springframework.ai.chat.messages.AssistantMessage
//import org.springframework.ai.chat.messages.SystemMessage
//import org.springframework.ai.chat.messages.ToolResponseMessage
//import org.springframework.ai.chat.prompt.Prompt
//import org.springframework.ai.model.ModelOptionsUtils
//import org.springframework.ai.model.tool.ToolCallingManager
//import org.springframework.stereotype.Service
//
///**
// * Service for managing conversation with AI using User-Controlled Tool Execution.
// *
// * This service implements the user-controlled tool execution pattern from Spring AI,
// * which gives us full control over the tool execution lifecycle:
// * 1. Call ChatModel with internalToolExecutionEnabled=false
// * 2. Check if response contains tool calls
// * 3. Approve tool calls (currently auto-approved)
// * 4. Execute tool calls via ToolCallingManager
// * 5. Send tool results back to model
// * 6. Repeat until no more tool calls
// *
// * This approach allows us to:
// * - See all intermediate tool_use and tool_result messages
// * - Persist them to the conversation tree
// * - Add UI approval in the future
// * - Have full visibility and control over tool execution
// *
// * TODO: Convert to Actor pattern to ensure single point of entry and sequential message processing
// */
//@Service
//class ConversationEngineService_v2(
//    private val chatModelProvider: ChatModelProvider,
//    private val agentDomainService: AgentDomainService,
//    private val toolCallingManager: ToolCallingManager,
//    private val toolApprovalService: ToolApprovalService,
//    private val conversationService: ConversationDomainService,
//    private val threadRepository: ThreadRepository,
//    private val threadMessageRepository: ThreadMessageRepository,
//    private val messageConversionService: MessageConversionService,
//    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
//    private val coroutineScope: CoroutineScope,
//    private val vectorMemoryService: VectorMemoryService,
//    private val knowledgeGraphService: KnowledgeGraphService?,
//) {
//    private val log = KLoggers.logger(this)
//
//
//    /**
//     * Stream messages in a conversation with user-controlled tool execution.
//     *
//     * This method implements the manual tool execution loop, giving us full control
//     * over when and how tools are executed.
//     *
//     * @param conversationId The conversation to append messages to
//     * @param userMessage The user message to send
//     * @param agent The agent to use for this conversation (provides system prompts)
//     * @return Flow of StreamUpdate events representing the conversation progress
//     */
//    suspend fun streamMessage(
//        conversationId: Conversation.Id,
//        userMessage: Conversation.Message,
//        agent: Agent,
//    ): Flow<StreamUpdate> = flow {
//        val conversation = conversationService.findById(conversationId)
//            ?: throw IllegalStateException("Conversation not found: $conversationId")
//        val projectPath = conversationService.getProjectPath(conversationId)
//        val messages = conversationService.loadCurrentMessages(conversationId)
//
//        // Persist user message once
//        conversationService.addMessage(conversationId, userMessage)
//
//        val systemPromptText = agentDomainService.assembleSystemPrompt(agent, projectPath)
//        val systemMessage = SystemMessage(systemPromptText)
//
//        val springHistory = messageConversionService.convertHistoryToSpringAI(messages + userMessage)
//        var currentPrompt = Prompt(
//            listOf(systemMessage) + springHistory, chatModelProvider.getChatModel(
//                AIProvider.valueOf(conversation.aiProvider),
//                conversation.modelName,
//                projectPath
//            ).defaultOptions
//        )
//
//        val chatModel = chatModelProvider.getChatModel(
//            AIProvider.valueOf(conversation.aiProvider),
//            conversation.modelName,
//            projectPath
//        )
//
//        var iterationCount = 0
//        val maxIterations = 50
//
//        while (iterationCount < maxIterations) {
//            iterationCount++
//
//            val chatResponse = chatModel.call(currentPrompt)
//            val finishReason = chatResponse.result.metadata.finishReason
//
//            // Log finish reason for diagnostics
//            if (finishReason == "LENGTH") {
//                log.warn { "Response truncated due to max_tokens limit in iteration $iterationCount" }
//            } else if (finishReason == "CONTENT_FILTER") {
//                log.warn { "Response blocked by content filter in iteration $iterationCount" }
//            }
//
//            if (chatResponse.hasToolCalls()) {
//                val assistantMessage = chatResponse.result.output
//                    ?: throw IllegalStateException("Expected AssistantMessage with tool calls")
//
//                val toolCalls = assistantMessage.toolCalls ?: emptyList()
//
//                // Persist AssistantMessage with tool calls before execution
//                val toolCallMessage = messageConversionService.fromSpringAI(assistantMessage)
//                    .copy(conversationId = conversationId)
//                conversationService.addMessage(conversationId, toolCallMessage)
//                emit(StreamUpdate.Chunk(toolCallMessage))
//
//                val approvalResult = toolApprovalService.approve(toolCalls)
//                if (approvalResult is ApprovalResult.Rejected) {
//                    emit(StreamUpdate.Error(IllegalStateException("Tool calls rejected: ${approvalResult.reason}")))
//                    break
//                }
//
//                val toolExecutionResult = try {
//                    toolCallingManager.executeToolCalls(currentPrompt, chatResponse)
//                } catch (e: Exception) {
//                    log.error(e) { "Tool execution failed: ${e.message}" }
//
//                    // Check if error is recoverable
//                    val isRecoverable = e is IllegalArgumentException || e is IllegalStateException
//
//                    if (!isRecoverable) {
//                        // Fatal error - stop processing
//                        emit(StreamUpdate.Error(e))
//                        break
//                    }
//
//                    // Recoverable error - send error response back to model
//                    val errorResponses = toolCalls.map { toolCall ->
//                        ToolResponseMessage.ToolResponse(
//                            toolCall.id(),
//                            toolCall.name(),
//                            "<tool_use_error>Error: ${e.message ?: e::class.simpleName}</tool_use_error>"
//                        )
//                    }
//
//                    val errorToolResponseMessage = ToolResponseMessage.builder()
//                        .responses(errorResponses)
//                        .metadata(emptyMap())
//                        .build()
//
//                    val errorConversationMessage = messageConversionService.fromSpringAI(errorToolResponseMessage)
//                        .copy(conversationId = conversationId)
//                    emit(StreamUpdate.Chunk(errorConversationMessage))
//                    conversationService.addMessage(conversationId, errorConversationMessage)
//
//                    currentPrompt = Prompt(
//                        currentPrompt.instructions + assistantMessage + errorToolResponseMessage,
//                        chatModel.defaultOptions
//                    )
//                    continue
//                }
//
//                // Persist only ToolResponseMessage (AssistantMessage already persisted above)
//                val toolResponseMessage = toolExecutionResult.conversationHistory().lastOrNull() as? ToolResponseMessage
//                    ?: throw IllegalStateException("Expected ToolResponseMessage as last message in history")
//
//                val toolResultMessage = messageConversionService.fromSpringAI(toolResponseMessage)
//                    .copy(conversationId = conversationId)
//                conversationService.addMessage(conversationId, toolResultMessage)
//                emit(StreamUpdate.Chunk(toolResultMessage))
//
//                // Check if tool execution returned direct result
//                if (toolExecutionResult.returnDirect()) {
//                    // Tool wants to return directly - check if there's a final text response
//                    val directResult = toolExecutionResult.conversationHistory().lastOrNull()
//                    if (directResult is AssistantMessage && !directResult.text.isNullOrBlank()) {
//                        val directMessage = messageConversionService.fromSpringAI(directResult)
//                            .copy(conversationId = conversationId)
//                        conversationService.addMessage(conversationId, directMessage)
//                        emit(StreamUpdate.Chunk(directMessage))
//                    }
//                    break
//                }
//
//                currentPrompt = Prompt(toolExecutionResult.conversationHistory(), chatModel.defaultOptions)
//            } else {
//                val finalAssistantMessage = chatResponse.result.output
//                if (finalAssistantMessage != null && !finalAssistantMessage.text.isNullOrBlank()) {
//                    val finalMessage = messageConversionService.fromSpringAI(finalAssistantMessage)
//                        .copy(conversationId = conversationId)
//                    conversationService.addMessage(conversationId, finalMessage)
//                    emit(StreamUpdate.Chunk(finalMessage))
//                }
//                break
//            }
//        }
//
//        if (iterationCount >= maxIterations) {
//            emit(StreamUpdate.Error(IllegalStateException("Tool execution loop exceeded maximum iterations")))
//        }
//    }
//
//    /**
//     * Remove additionalContent (images) from ToolResponseMessage before saving to DB.
//     *
//     * IMPORTANT: Must preserve JSON structure! Spring AI's GoogleGenAiChatModel.parseJsonToMap()
//     * expects valid JSON when sending FunctionResponse back to Gemini.
//     *
//     * Previous bug: Extracted only "text" field, breaking JSON → Gemini didn't receive tool results.
//     */
//    private fun removeAdditionalContentFromToolResponse(message: ToolResponseMessage): ToolResponseMessage {
//        val objectMapper = ObjectMapper()
//
//        val cleanedResponses = message.responses.map { response ->
//            try {
//                val responseData = response.responseData()
//
//                val cleanedData = when {
//                    // Plain text, not JSON - leave as is
//                    !responseData.trim().startsWith("{") && !responseData.trim().startsWith("[") -> {
//                        responseData
//                    }
//
//                    // JSON array - keep only text items, but preserve JSON array structure
//                    responseData.trim().startsWith("[") -> {
//                        val jsonNode = objectMapper.readTree(responseData)
//                        if (jsonNode.isArray) {
//                            val textItems = jsonNode.filter {
//                                it.has("type") && it.get("type").asText() == "text"
//                            }
//                            objectMapper.writeValueAsString(textItems)
//                        } else {
//                            responseData
//                        }
//                    }
//
//                    // JSON object - remove only additionalContent field, preserve full JSON structure
//                    else -> {
//                        val data = ModelOptionsUtils.jsonToMap(responseData)
//                        val cleaned = data.toMutableMap()
//                        cleaned.remove("additionalContent")
//                        objectMapper.writeValueAsString(cleaned)
//                    }
//                }
//
//                ToolResponseMessage.ToolResponse(
//                    response.id(),
//                    response.name(),
//                    cleanedData
//                )
//            } catch (e: Exception) {
//                log.warn(e) { "Failed to parse tool response data for cleaning" }
//                response
//            }
//        }
//
//        return ToolResponseMessage.builder()
//            .responses(cleanedResponses)
//            .metadata(message.metadata)
//            .build()
//    }
//
//    /**
//     * Remember current thread messages to vector memory.
//     * This method triggers incremental embedding of thread messages for semantic recall.
//     */
//    suspend fun rememberCurrentThread(conversationId: Conversation.Id) {
//        try {
//            val conversation = conversationService.findById(conversationId)
//                ?: throw IllegalStateException("Conversation not found: $conversationId")
//
//            vectorMemoryService.rememberThread(conversation.currentThread.value)
//            log.info { "Remembered thread ${conversation.currentThread} for conversation $conversationId" }
//        } catch (e: Exception) {
//            log.error(e) { "Failed to remember thread for conversation $conversationId: ${e.message}" }
//            throw e
//        }
//    }
//
//    /**
//     * Add current thread to knowledge graph (Phase 2).
//     * This method triggers entity and relationship extraction from conversation.
//     */
//    suspend fun addToGraphCurrentThread(conversationId: Conversation.Id) {
//        if (knowledgeGraphService == null) {
//            log.warn { "KnowledgeGraphService not available (knowledge-graph.enabled=false)" }
//            return
//        }
//
//        try {
//            val conversation = conversationService.findById(conversationId)
//                ?: throw IllegalStateException("Conversation not found: $conversationId")
//
//            val threadMessages = threadMessageRepository.getMessagesByThread(conversation.currentThread)
//                .filter { message ->
//                    message.role in listOf(Conversation.Message.Role.USER, Conversation.Message.Role.ASSISTANT)
//                }
//                .joinToString("\n\n") { message ->
//                    "${message.role}: ${extractGraphTextContent(message)}"
//                }
//
//            log.info { "Adding thread ${conversation.currentThread} to knowledge graph for conversation $conversationId" }
//
//            val result = knowledgeGraphService.extractAndSaveToGraph(threadMessages)
//            log.info { "Knowledge graph update result: $result" }
//        } catch (e: Exception) {
//            log.error(e) { "Failed to add thread to graph for conversation $conversationId: ${e.message}" }
//            throw e
//        }
//    }
//
//    private fun extractGraphTextContent(message: Conversation.Message): String {
//        return message.content.mapNotNull { contentItem ->
//            when (contentItem) {
//                is Conversation.Message.ContentItem.UserMessage -> contentItem.text
//                is Conversation.Message.ContentItem.AssistantMessage -> contentItem.structured.fullText
//                else -> null
//            }
//        }.joinToString("\\n")
//    }
//
//    /**
//     * Stream update events representing conversation progress.
//     */
//    sealed class StreamUpdate {
//        data class Chunk(val message: Conversation.Message) : StreamUpdate()
//        data class Error(val exception: Throwable) : StreamUpdate()
//    }
//}
