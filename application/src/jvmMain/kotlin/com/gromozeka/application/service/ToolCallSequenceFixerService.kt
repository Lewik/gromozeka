package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadMessageLink
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

    private data class FixedMessageSequence(
        val messages: List<Conversation.Message>,
        val addedResults: Int,
        val convertedResults: Int,
    )
    
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

        val fixedSequence = buildFixedMessageSequence(
            messages = messages,
            conversationId = conversationId
        )

        if (fixedSequence.addedResults == 0 && fixedSequence.convertedResults == 0) {
            log.debug { "No non-sequential pairs found in conversation $conversationId" }
            return FixResult(fixed = false, addedResults = 0, convertedResults = 0)
        }

        val fixedMessages = fixedSequence.messages
        val addedResults = fixedSequence.addedResults
        val convertedResults = fixedSequence.convertedResults

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

        val existingMessageIds = messageRepository.findByIds(fixedMessages.map { it.id })
            .mapTo(mutableSetOf()) { it.id }

        fixedMessages.filter { it.id !in existingMessageIds }.forEach { message ->
            messageRepository.save(message)
        }

        threadMessageRepository.addBatch(
            fixedMessages.mapIndexed { index, message ->
                ThreadMessageLink(
                    threadId = newThread.id,
                    messageId = message.id,
                    position = index
                )
            }
        )
        
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
    ): FixedMessageSequence {
        val fixedMessages = mutableListOf<Conversation.Message>()
        var pendingToolCalls = emptyMap<ContentItem.ToolCall.Id, ContentItem.ToolCall>()
        var addedResults = 0
        var convertedResults = 0

        messages.forEach { message ->
            val toolCalls = message.content.filterIsInstance<ContentItem.ToolCall>()
            val toolResults = message.content.filterIsInstance<ContentItem.ToolResult>()
            val unmatchedResults = toolResults.filter { it.toolUseId !in pendingToolCalls.keys }
            val matchedResultIds = toolResults
                .filter { it.toolUseId in pendingToolCalls.keys }
                .mapTo(mutableSetOf()) { it.toolUseId }

            val remainingContent = if (unmatchedResults.isEmpty()) {
                message.content
            } else {
                message.content.filterNot { content ->
                    content is ContentItem.ToolResult && content.toolUseId !in pendingToolCalls.keys
                }
            }

            if (remainingContent.isNotEmpty()) {
                fixedMessages += if (remainingContent.size == message.content.size) {
                    message
                } else {
                    message.copy(content = remainingContent)
                }
            }

            if (unmatchedResults.isNotEmpty()) {
                log.debug {
                    "Converting ${unmatchedResults.size} orphaned ToolResult(s) in message ${message.id.value}"
                }
                fixedMessages += createConvertedOrphanedToolResultsMessage(conversationId, unmatchedResults)
                convertedResults += unmatchedResults.size
            }

            val unmatchedCallIds = pendingToolCalls.keys - matchedResultIds
            unmatchedCallIds.forEach { callId ->
                val toolCall = pendingToolCalls.getValue(callId)
                log.debug {
                    "Inserting error ToolResult for orphaned ToolCall: " +
                        "${toolCall.call.name} (id=${callId.value})"
                }
                fixedMessages += createErrorToolResult(conversationId, toolCall)
                addedResults++
            }

            pendingToolCalls = toolCalls.associateBy { it.id }
        }

        pendingToolCalls.values.forEach { toolCall ->
            log.debug {
                "Inserting error ToolResult for final orphaned ToolCall: " +
                    "${toolCall.call.name} (id=${toolCall.id.value})"
            }
            fixedMessages += createErrorToolResult(conversationId, toolCall)
            addedResults++
        }

        return FixedMessageSequence(
            messages = fixedMessages,
            addedResults = addedResults,
            convertedResults = convertedResults,
        )
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
    
    private fun createConvertedOrphanedToolResultsMessage(
        conversationId: Conversation.Id,
        orphanedResults: List<ContentItem.ToolResult>
    ): Conversation.Message {
        val convertedContent = orphanedResults.map { orphanedResult ->
            val resultText = orphanedResult.result.joinToString("\n") { data ->
                when (data) {
                    is ContentItem.ToolResult.Data.Text -> data.content
                    is ContentItem.ToolResult.Data.Base64Data -> "[Base64 data: ${data.mediaType}]"
                    is ContentItem.ToolResult.Data.UrlData -> "[URL: ${data.url}]"
                    is ContentItem.ToolResult.Data.FileData -> "[File: ${data.fileId}]"
                }
            }

            val convertedText = """
                [Converted from orphaned ToolResult: ${orphanedResult.toolName}]
                
                This message was originally a tool result without corresponding tool call.
                Original result:
                
                $resultText
            """.trimIndent()

            ContentItem.AssistantMessage(
                structured = Conversation.Message.StructuredText(fullText = convertedText),
                state = BlockState.COMPLETE
            )
        }

        return Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = convertedContent,
            createdAt = Clock.System.now(),
        )
    }
}
