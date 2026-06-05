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
    private companion object {
        const val OPENAI_REASONING_ITEMS_METADATA_KEY = "openaiReasoningItems"
    }

    private data class FixedMessageSequence(
        val messages: List<Conversation.Message>,
        val addedResults: Int,
        val convertedResults: Int,
        val mergedToolCallMessages: Int,
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
        val convertedResults: Int,
        val mergedToolCallMessages: Int,
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
        val replayWindow = messages.toFixableReplayWindow()

        val fixedSequence = buildFixedMessageSequence(
            messages = replayWindow.messages,
            conversationId = conversationId
        )

        if (
            fixedSequence.addedResults == 0 &&
            fixedSequence.convertedResults == 0 &&
            fixedSequence.mergedToolCallMessages == 0
        ) {
            log.debug { "No non-sequential pairs found in conversation $conversationId" }
            return FixResult(
                fixed = false,
                addedResults = 0,
                convertedResults = 0,
                mergedToolCallMessages = 0,
            )
        }

        val fixedMessages = replayWindow.preservedPrefix + fixedSequence.messages
        val addedResults = fixedSequence.addedResults
        val convertedResults = fixedSequence.convertedResults
        val mergedToolCallMessages = fixedSequence.mergedToolCallMessages

        log.warn { 
            "Found non-sequential pairs in conversation $conversationId: " +
            "merged $mergedToolCallMessages tool-call messages, " +
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
            "merged $mergedToolCallMessages tool-call messages, " +
            "added $addedResults error results, converted $convertedResults orphaned results, " +
            "created new thread ${newThread.id}" 
        }

        return FixResult(
            fixed = true,
            addedResults = addedResults,
            convertedResults = convertedResults,
            mergedToolCallMessages = mergedToolCallMessages,
        )
    }
    
    private fun buildFixedMessageSequence(
        messages: List<Conversation.Message>,
        conversationId: Conversation.Id
    ): FixedMessageSequence {
        val normalizedSequence = messages.mergeAdjacentToolCallMessages()
        val fixedMessages = mutableListOf<Conversation.Message>()
        var pendingToolCalls = emptyMap<ContentItem.ToolCall.Id, ContentItem.ToolCall>()
        var addedResults = 0
        var convertedResults = 0

        normalizedSequence.forEach { message ->
            val toolCalls = message.content.filterIsInstance<ContentItem.ToolCall>()
            val toolResults = message.content.filterIsInstance<ContentItem.ToolResult>()
            val unmatchedResults = toolResults.filter { it.toolUseId !in pendingToolCalls.keys }
            val matchedResultIds = toolResults
                .filter { it.toolUseId in pendingToolCalls.keys }
                .mapTo(mutableSetOf()) { it.toolUseId }

            if (pendingToolCalls.isNotEmpty() && matchedResultIds.isEmpty()) {
                pendingToolCalls.values.forEach { toolCall ->
                    log.debug {
                        "Inserting error ToolResult for orphaned ToolCall before message ${message.id.value}: " +
                            "${toolCall.call.name} (id=${toolCall.id.value})"
                    }
                    fixedMessages += createErrorToolResult(conversationId, toolCall)
                    addedResults++
                }
                pendingToolCalls = emptyMap()
            }

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
            mergedToolCallMessages = messages.size - normalizedSequence.size,
        )
    }

    private data class ReplayWindow(
        val preservedPrefix: List<Conversation.Message>,
        val messages: List<Conversation.Message>,
    )

    private fun List<Conversation.Message>.toFixableReplayWindow(): ReplayWindow {
        val compactionAnchorIndex = indexOfLast { it.providerMetadata.containsCompactionReplayItem() }
        if (compactionAnchorIndex < 0) {
            return ReplayWindow(
                preservedPrefix = emptyList(),
                messages = this,
            )
        }

        log.info {
            "Tool call sequence fixer limited to post-compaction replay window: " +
                "preservedMessages=$compactionAnchorIndex, fixableMessages=${size - compactionAnchorIndex}"
        }

        return ReplayWindow(
            preservedPrefix = take(compactionAnchorIndex),
            messages = drop(compactionAnchorIndex),
        )
    }

    private fun List<Conversation.Message>.mergeAdjacentToolCallMessages(): List<Conversation.Message> {
        if (size < 2) return this

        val merged = mutableListOf<Conversation.Message>()

        forEach { message ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.canMergeAdjacentToolCallsWith(message)) {
                merged[merged.lastIndex] = previous.copy(
                    content = previous.content + message.content,
                    providerMetadata = kotlinx.serialization.json.JsonObject(
                        previous.providerMetadata.toMap() + message.providerMetadata.toMap()
                    ),
                )
            } else {
                merged += message
            }
        }

        return merged
    }

    private fun Conversation.Message.canMergeAdjacentToolCallsWith(
        other: Conversation.Message,
    ): Boolean {
        return isToolCallOnlyAssistantMessage() && other.isToolCallOnlyAssistantMessage()
    }

    private fun Conversation.Message.isToolCallOnlyAssistantMessage(): Boolean {
        return role == Conversation.Message.Role.ASSISTANT &&
            error == null &&
            content.isNotEmpty() &&
            content.all { it is ContentItem.ToolCall }
    }

    private fun kotlinx.serialization.json.JsonObject.containsCompactionReplayItem(): Boolean {
        val reasoningItems = this[OPENAI_REASONING_ITEMS_METADATA_KEY]
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            ?: return false
        return reasoningItems.any { item ->
            val type = (item as? kotlinx.serialization.json.JsonObject)
                ?.get("type")
                ?.toString()
                ?.trim('"')
            type == "compaction" || type == "compaction_summary"
        }
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
