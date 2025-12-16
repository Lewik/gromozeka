package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for fixing non-sequential ToolCall/ToolResult pairs in conversation history.
 * 
 * Anthropic API requires that ToolResult appears IMMEDIATELY after ToolCall.
 * This service inserts missing ToolResults or converts orphaned ToolResults to AssistantMessages.
 */
@Service
class ToolCallSequenceFixerService(
    private val conversationService: ConversationDomainService,
    private val conversationRepository: ConversationRepository,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val threadMessageRepository: ThreadMessageRepository
) {
    private val log = KLoggers.logger(this)
    
    /**
     * Result of fixing operation.
     * 
     * @property fixed true if any changes were made
     * @property addedResults count of error ToolResults added for orphaned ToolCalls
     * @property convertedResults count of ToolResults converted to AssistantMessages
     */
    data class FixResult(
        val fixed: Boolean,
        val addedResults: Int,
        val convertedResults: Int
    )
    
    /**
     * Fix non-sequential ToolCall/ToolResult pairs in conversation.
     * 
     * Strategy:
     * 1. For orphaned ToolCall: insert error ToolResult IMMEDIATELY after it
     * 2. For orphaned ToolResult: convert to AssistantMessage with explanation
     * 
     * Creates new thread with fixed messages and saves to database.
     * 
     * @param conversationId conversation to fix
     * @return fix result with statistics
     */
    @Transactional
    suspend fun fixNonSequentialPairs(conversationId: Conversation.Id): FixResult {
        val messages = conversationService.loadCurrentMessages(conversationId)
        
        // Build fixed message sequence (single pass)
        val fixedMessages = buildFixedMessageSequence(
            messages = messages,
            conversationId = conversationId
        )
        
        // Check if any changes were made
        if (fixedMessages.size == messages.size) {
            log.debug { "No non-sequential pairs found in conversation $conversationId" }
            return FixResult(fixed = false, addedResults = 0, convertedResults = 0)
        }
        
        val addedResults = fixedMessages.size - messages.size
        val convertedResults = fixedMessages.count { msg ->
            msg.role == Conversation.Message.Role.ASSISTANT &&
            msg.content.any { it is ContentItem.AssistantMessage && 
                             it.structured.fullText.startsWith("[Converted from orphaned ToolResult:") }
        }
        
        log.warn { 
            "Found non-sequential pairs in conversation $conversationId: " +
            "added $addedResults messages, converted $convertedResults orphaned results" 
        }
        
        // Create new thread with fixed messages
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        
        val now = Clock.System.now()
        val newThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = conversationId,
            originalThread = conversation.currentThread,
            createdAt = now,
            updatedAt = now
        )
        
        threadRepository.save(newThread)
        
        // Save fixed messages and create thread-message links
        fixedMessages.forEachIndexed { index, message ->
            messageRepository.save(message)
            threadMessageRepository.add(newThread.id, message.id, index)
        }
        
        // Update conversation to use new thread
        conversationRepository.updateCurrentThread(conversationId, newThread.id)
        
        log.info { 
            "Fixed non-sequential pairs in conversation $conversationId: " +
            "added $addedResults error results, converted $convertedResults orphaned results, " +
            "created new thread ${newThread.id}" 
        }
        
        return FixResult(
            fixed = true,
            addedResults = addedResults,
            convertedResults = convertedResults
        )
    }
    
    private fun buildFixedMessageSequence(
        messages: List<Conversation.Message>,
        conversationId: Conversation.Id
    ): List<Conversation.Message> {
        return sequence {
            var pendingToolCalls = emptyMap<ContentItem.ToolCall.Id, ContentItem.ToolCall>()
            
            messages.forEach { message ->
                val toolCalls = message.content.filterIsInstance<ContentItem.ToolCall>()
                val toolResults = message.content.filterIsInstance<ContentItem.ToolResult>()
                
                val matchedResultIds = toolResults.map { it.toolUseId }.toSet()
                val unmatchedResults = toolResults.filter { it.toolUseId !in pendingToolCalls.keys }
                
                if (unmatchedResults.isNotEmpty()) {
                    log.debug { 
                        "Converting ${unmatchedResults.size} orphaned ToolResult(s) in message ${message.id.value}" 
                    }
                    yield(convertOrphanedToolResults(message, unmatchedResults))
                } else {
                    yield(message)
                }
                
                val unmatchedCallIds = pendingToolCalls.keys - matchedResultIds
                unmatchedCallIds.forEach { callId ->
                    val toolCall = pendingToolCalls[callId]!!
                    log.debug { 
                        "Inserting error ToolResult for orphaned ToolCall: " +
                        "${toolCall.call.name} (id=${callId.value})" 
                    }
                    yield(createErrorToolResult(conversationId, toolCall))
                }
                
                pendingToolCalls = toolCalls.associateBy { it.id }
            }
            
            pendingToolCalls.values.forEach { toolCall ->
                log.debug { 
                    "Inserting error ToolResult for final orphaned ToolCall: " +
                    "${toolCall.call.name} (id=${toolCall.id.value})" 
                }
                yield(createErrorToolResult(conversationId, toolCall))
            }
        }.toList()
    }
    
    private fun createErrorToolResult(
        conversationId: Conversation.Id,
        toolCall: ContentItem.ToolCall
    ): Conversation.Message {
        return Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(
                ContentItem.ToolResult(
                    toolUseId = toolCall.id,
                    toolName = toolCall.call.name,
                    result = listOf(
                        ContentItem.ToolResult.Data.Text("Tool execution was interrupted or cancelled")
                    ),
                    isError = true,
                    state = BlockState.COMPLETE
                )
            ),
            createdAt = Clock.System.now()
        )
    }
    
    private fun convertOrphanedToolResults(
        message: Conversation.Message,
        orphanedResults: List<ContentItem.ToolResult>
    ): Conversation.Message {
        val orphanedIds = orphanedResults.map { it.toolUseId }.toSet()
        
        val updatedContent = message.content.map { content ->
            if (content is ContentItem.ToolResult && content.toolUseId in orphanedIds) {
                // Extract result text
                val resultText = content.result.joinToString("\n") { data ->
                    when (data) {
                        is ContentItem.ToolResult.Data.Text -> data.content
                        is ContentItem.ToolResult.Data.Base64Data -> "[Base64 data: ${data.mediaType}]"
                        is ContentItem.ToolResult.Data.UrlData -> "[URL: ${data.url}]"
                        is ContentItem.ToolResult.Data.FileData -> "[File: ${data.fileId}]"
                    }
                }
                
                val convertedText = """
                    [Converted from orphaned ToolResult: ${content.toolName}]
                    
                    This message was originally a tool result without corresponding tool call.
                    Original result:
                    
                    $resultText
                """.trimIndent()
                
                ContentItem.AssistantMessage(
                    structured = Conversation.Message.StructuredText(fullText = convertedText),
                    state = BlockState.COMPLETE
                )
            } else {
                content
            }
        }
        
        log.debug { 
            "Converted ${orphanedResults.size} orphaned ToolResult(s) in message ${message.id.value}" 
        }
        
        return message.copy(
            id = Conversation.Message.Id(uuid7()), // New ID for converted message
            role = Conversation.Message.Role.ASSISTANT,
            content = updatedContent,
            createdAt = Clock.System.now()
        )
    }
}
