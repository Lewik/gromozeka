package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ToolSequenceMessageHeader
import com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlin.time.Clock
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
    private val conversationRepository: ConversationRepository,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val threadMessageRepository: ThreadMessageRepository
) {
    private val log = KLoggers.logger(this)

    internal data class FixedMessageSequence(
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
        val conversation = conversationRepository.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val expectedThreadId = conversation.currentThread
        val replayWindow = threadMessageRepository.invalidToolSequenceWindow(expectedThreadId)
        if (replayWindow.isEmpty()) {
            log.debug { "No non-sequential pairs found in conversation $conversationId" }
            return FixResult(
                fixed = false,
                addedResults = 0,
                convertedResults = 0,
                mergedToolCallMessages = 0,
            )
        }
        val bodyIds = replayWindow.filter { header ->
            header.facts.callIds.isNotEmpty() || header.facts.resultIds.isNotEmpty()
        }.map(ToolSequenceMessageHeader::id)
        val bodies = threadMessageRepository.compactionBodies(expectedThreadId, bodyIds).associateBy { it.id }
        check(bodies.keys == bodyIds.toSet()) { "Tool sequence repair could not load every tool-bearing message" }
        val messages = replayWindow.map { header -> bodies[header.id] ?: header.placeholder(conversationId) }

        val fixedSequence = buildFixedMessageSequence(
            messages = messages,
            conversationId = conversationId
        )

        check(!(
            fixedSequence.addedResults == 0 &&
            fixedSequence.convertedResults == 0 &&
            fixedSequence.mergedToolCallMessages == 0
        )) { "SQL detected an invalid tool sequence that the repair did not change" }

        val addedResults = fixedSequence.addedResults
        val convertedResults = fixedSequence.convertedResults
        val mergedToolCallMessages = fixedSequence.mergedToolCallMessages

        log.warn { 
            "Found non-sequential pairs in conversation $conversationId: " +
            "merged $mergedToolCallMessages tool-call messages, " +
            "added $addedResults messages, converted $convertedResults orphaned results" 
        }
        
        check(conversationRepository.findById(conversationId)?.currentThread == expectedThreadId) {
            "Conversation thread changed while planning tool sequence repair"
        }
        
        val now = Clock.System.now()
        val newThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = conversationId,
            originalThread = expectedThreadId,
            createdAt = now,
            updatedAt = now
        )
        
        threadRepository.save(newThread)

        val sourceIds = replayWindow.mapTo(mutableSetOf(), ToolSequenceMessageHeader::id)
        val newMessages = fixedSequence.messages.filter { it.id !in sourceIds }
        check(newMessages.map { it.id }.distinct().size == newMessages.size) {
            "Tool sequence repair produced duplicate immutable message IDs"
        }
        messageRepository.saveBatch(newMessages)

        threadMessageRepository.copyPrefixAndAddSuffix(
            sourceThreadId = expectedThreadId,
            targetThreadId = newThread.id,
            suffixStartPosition = replayWindow.first().position,
            suffixMessageIds = fixedSequence.messages.map { it.id },
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
    
    internal fun buildFixedMessageSequence(
        messages: List<Conversation.Message>,
        conversationId: Conversation.Id
    ): FixedMessageSequence {
        val normalizedSequence = messages.mergeAdjacentToolCallMessages()
        val fixedMessages = mutableListOf<Conversation.Message>()
        var pendingToolCalls = emptyMap<ContentItem.ToolCall.Id, ContentItem.ToolCall>()
        var addedResults = 0
        var convertedResults = 0
        val completedCallIds = normalizedSequence.flatMap { it.content }.filterIsInstance<ContentItem.ToolResult>()
            .mapTo(mutableSetOf()) { it.toolUseId }
        val providerManagedCallIds = normalizedSequence.filter {
            it.providerMetadata[AI_PROVIDER_MANAGED_TOOL_METADATA_KEY] == JsonPrimitive(true)
        }.flatMap { it.content }.filterIsInstance<ContentItem.ToolCall>().mapTo(mutableSetOf()) { it.id }

        normalizedSequence.forEach { message ->
            if (message.providerMetadata[AI_PROVIDER_MANAGED_TOOL_METADATA_KEY] == JsonPrimitive(true) ||
                message.content.isNotEmpty() && message.content.all { it is ContentItem.ToolResult && it.toolUseId in providerManagedCallIds }) {
                fixedMessages += message
                message.content.filterIsInstance<ContentItem.ToolCall>().filter { it.id !in completedCallIds }.forEach {
                    fixedMessages += createErrorToolResult(conversationId, it)
                    addedResults++
                }
                return@forEach
            }
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

            // Body-free placeholders for unchanged non-tool rows have empty content; retain their original links.
            if (remainingContent.isNotEmpty() || message.content.isEmpty()) {
                fixedMessages += if (remainingContent.size == message.content.size) {
                    message
                } else {
                    message.copy(
                        id = Conversation.Message.Id(uuid7()),
                        originalIds = (message.originalIds + message.id).distinct(),
                        content = remainingContent,
                        createdAt = Clock.System.now(),
                    )
                }
            }

            if (unmatchedResults.isNotEmpty()) {
                log.debug {
                    "Converting ${unmatchedResults.size} orphaned ToolResult(s) in message ${message.id.value}"
                }
                fixedMessages += createConvertedOrphanedToolResultsMessage(conversationId, message, unmatchedResults)
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

    private fun List<Conversation.Message>.mergeAdjacentToolCallMessages(): List<Conversation.Message> {
        if (size < 2) return this

        val merged = mutableListOf<Conversation.Message>()
        var index = 0
        while (index < size) {
            val group = mutableListOf(this[index])
            while (index + group.size < size && group.last().canMergeAdjacentToolCallsWith(this[index + group.size])) {
                group += this[index + group.size]
            }
            merged += if (group.size == 1) group.single() else group.first().copy(
                id = Conversation.Message.Id(uuid7()),
                originalIds = group.flatMap { it.originalIds + it.id }.distinct(),
                content = group.flatMap { it.content },
                providerMetadata = JsonObject(group.fold(emptyMap()) { metadata, message ->
                    metadata + message.providerMetadata.toMap()
                }),
                createdAt = Clock.System.now(),
            )
            index += group.size
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
            providerMetadata[AI_PROVIDER_MANAGED_TOOL_METADATA_KEY] != JsonPrimitive(true) &&
            error == null &&
            content.isNotEmpty() &&
            content.all { it is ContentItem.ToolCall }
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
        sourceMessage: Conversation.Message,
        orphanedResults: List<ContentItem.ToolResult>
    ): Conversation.Message {
        val convertedContent = orphanedResults.map { orphanedResult ->
            val resultText = orphanedResult.result.joinToString("\n") { data ->
                when (data) {
                    is ContentItem.ToolResult.Data.Text -> data.content
                    is ContentItem.ToolResult.Data.Base64Data -> "[Base64 data: ${data.mediaType}]"
                    is ContentItem.ToolResult.Data.UrlData -> "[URL: ${data.url}]"
                    is ContentItem.ToolResult.Data.ArtifactData -> "[Artifact: ${data.artifact.fileName}]"
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
            originalIds = (sourceMessage.originalIds + sourceMessage.id).distinct(),
            role = Conversation.Message.Role.ASSISTANT,
            content = convertedContent,
            createdAt = Clock.System.now(),
        )
    }

    private fun ToolSequenceMessageHeader.placeholder(
        conversationId: Conversation.Id,
    ): Conversation.Message {
        check(facts.callIds.isEmpty() && facts.resultIds.isEmpty()) {
            "Tool-bearing message body was not loaded: ${id.value}"
        }
        check(!facts.fullCompactionAnchor) { "The latest full checkpoint must not be in the repair window" }
        return Conversation.Message(
            id = id,
            conversationId = conversationId,
            role = role,
            content = if (role == Conversation.Message.Role.RUNTIME) {
                listOf(ContentItem.RuntimeJson(JsonObject(emptyMap())))
            } else {
                emptyList()
            },
            providerMetadata = if (facts.providerManaged) {
                JsonObject(mapOf(AI_PROVIDER_MANAGED_TOOL_METADATA_KEY to JsonPrimitive(true)))
            } else {
                JsonObject(emptyMap())
            },
            createdAt = Clock.System.now(),
        )
    }
}
