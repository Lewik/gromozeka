package com.gromozeka.application.service.memory

import com.gromozeka.application.service.AiConversationMessageMapper
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryThreadContext
import com.gromozeka.domain.service.AiRuntime
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class MemoryThreadContextCompactor(
    private val runtime: AiRuntime,
    private val preCompactThresholdTokens: Int? = null,
) {
    private val log = KLoggers.logger(this)

    suspend fun compactIfNeeded(
        context: MemoryThreadContext,
        targetSourceLabel: String,
        logContext: String,
    ): MemoryThreadContext {
        val targetIndex = context.messages.indexOfFirst { it.id == context.targetMessageId }
        if (targetIndex <= 0) return context

        val priorMessages = context.messages.take(targetIndex)
        val target = context.messages[targetIndex]
        val priorChars = priorMessages.sumOf { it.memoryCompactionCharCount() }
        val estimatedInputTokens = (priorChars + target.memoryCompactionCharCount())
            .estimatedMemoryContextTokens() + MEMORY_STAGE_PROMPT_OVERHEAD_TOKENS
        val shouldCompact = preCompactThresholdTokens
            ?.let { estimatedInputTokens > it }
            ?: (priorChars > PRIOR_CONTEXT_COMPACTION_THRESHOLD_CHARS)
        if (!shouldCompact) return context

        val tailMessages = priorMessages.takeLastWithinMemoryCompactionBudget(rawTailContextChars())
        val prefixMessages = priorMessages.dropLast(tailMessages.size)
        if (prefixMessages.isEmpty()) return context

        log.info {
            "Memory thread context compaction start: $logContext target=${context.targetMessageId.value} " +
                "priorMessages=${priorMessages.size} prefixMessages=${prefixMessages.size} tailMessages=${tailMessages.size} " +
                "priorChars=$priorChars estimatedInputTokens=$estimatedInputTokens " +
                "thresholdTokens=${preCompactThresholdTokens ?: "char-fallback"} targetSource=$targetSourceLabel"
        }

        val compacted = runCatching {
            val targetText = target.renderForMemoryCompaction(TARGET_CONTEXT_CHARS)
            val chunks = prefixMessages.chunkForMemoryCompaction(compactionChunkChars())
            val chunkDigests = chunks.mapIndexed { index, chunk ->
                compactChunk(
                    chunk = chunk,
                    chunkIndex = index,
                    chunkCount = chunks.size,
                    targetText = targetText,
                    targetSourceLabel = targetSourceLabel,
                    logContext = logContext,
                )
            }
            if (chunkDigests.size == 1) {
                chunkDigests.single()
            } else {
                mergeChunkDigests(
                    chunkDigests = chunkDigests,
                    targetText = targetText,
                    targetSourceLabel = targetSourceLabel,
                    logContext = logContext,
                )
            }
        }.getOrElse { error ->
            log.warn(error) {
                "Memory thread context compaction failed; falling back to raw tail only: $logContext " +
                    "target=${context.targetMessageId.value} priorChars=$priorChars error=${error.message}"
            }
            "Older prior conversation was omitted because focused memory compaction failed. Use the raw recent tail and TARGET_MESSAGE only."
        }

        val syntheticContextMessage = Conversation.Message(
            id = Conversation.Message.Id("memory-focused-context:${context.targetMessageId.value}"),
            conversationId = context.conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(
                Conversation.Message.ContentItem.UserMessage(
                    """
                    FOCUSED_PRIOR_CONTEXT_FOR_MEMORY:
                    $compacted
                    """.trimIndent()
                )
            ),
            providerMetadata = JsonObject(
                mapOf(
                    "syntheticKind" to JsonPrimitive("memory_focused_context"),
                    "targetMessageId" to JsonPrimitive(context.targetMessageId.value),
                )
            ),
            createdAt = Clock.System.now(),
        )

        val compactedMessages = listOf(syntheticContextMessage) + tailMessages + target
        log.info {
            "Memory thread context compaction completed: $logContext target=${context.targetMessageId.value} " +
                "messages=${context.messages.size}->${compactedMessages.size} priorChars=$priorChars " +
                "estimatedInputTokens=$estimatedInputTokens thresholdTokens=${preCompactThresholdTokens ?: "char-fallback"} " +
                "digestChars=${compacted.length} tailMessages=${tailMessages.size}"
        }

        return context.copy(messages = compactedMessages)
    }

    private fun rawTailContextChars(): Int =
        preCompactThresholdTokens
            ?.toMemoryContextChars(reservedTokens = MEMORY_STAGE_PROMPT_OVERHEAD_TOKENS)
            ?.coerceIn(MIN_RAW_TAIL_CONTEXT_CHARS, RAW_TAIL_CONTEXT_CHARS)
            ?: RAW_TAIL_CONTEXT_CHARS

    private fun compactionChunkChars(): Int =
        preCompactThresholdTokens
            ?.toMemoryContextChars(reservedTokens = MEMORY_STAGE_PROMPT_OVERHEAD_TOKENS + TARGET_CONTEXT_CHARS.estimatedMemoryContextTokens())
            ?.coerceIn(MIN_COMPACTION_CHUNK_CHARS, COMPACTION_CHUNK_CHARS)
            ?: COMPACTION_CHUNK_CHARS

    private suspend fun compactChunk(
        chunk: List<Conversation.Message>,
        chunkIndex: Int,
        chunkCount: Int,
        targetText: String,
        targetSourceLabel: String,
        logContext: String,
    ): String {
        val renderedChunk = chunk.joinToString("\n\n") { it.renderForMemoryCompaction(MESSAGE_CONTEXT_CHARS) }
        val prompt = """
            MemoryThreadContextCompactor v1.

            Distill this prior conversation chunk only to help later memory stages interpret TARGET_MESSAGE.
            Preserve entity identities, pronoun referents, corrections, decisions, user preferences, project constraints, temporal changes, and unresolved facts that can affect TARGET_MESSAGE.
            Drop resolved tool chatter, repetitive assistant acknowledgements, low-value debugging noise, and raw file dumps unless TARGET_MESSAGE depends on them.
            Do not summarize TARGET_MESSAGE itself. It is provided verbatim to later stages.
            Return concise plain text bullets. No JSON.

            TARGET_SOURCE_LABEL:
            $targetSourceLabel

            TARGET_MESSAGE:
            $targetText

            PRIOR_CONTEXT_CHUNK ${chunkIndex + 1}/$chunkCount:
            $renderedChunk
        """.trimIndent()

        val response = runtime.callMemoryStageWithRetry(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(memoryCompactionTaskMessage(prompt)),
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.THREAD_CONTEXT_COMPACTION_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    toolContext = mapOf(
                        "memoryThreadContextCompaction" to true,
                        "memoryThreadContextCompactionChunk" to chunkIndex + 1,
                        "memoryThreadContextCompactionChunks" to chunkCount,
                    ),
                ),
            ),
            stageName = "MemoryThreadContextCompactor",
            logContext = "$logContext chunk=${chunkIndex + 1}/$chunkCount promptChars=${prompt.length}",
        )

        return AiConversationMessageMapper.extractAssistantText(response).trim()
    }

    private suspend fun mergeChunkDigests(
        chunkDigests: List<String>,
        targetText: String,
        targetSourceLabel: String,
        logContext: String,
    ): String {
        val prompt = """
            MemoryThreadContextCompactorMerge v1.

            Merge focused prior-context digests into one concise context block for memory stages.
            Preserve details needed to interpret TARGET_MESSAGE. Remove duplicate bullets.
            Do not summarize TARGET_MESSAGE itself. Return concise plain text bullets. No JSON.

            TARGET_SOURCE_LABEL:
            $targetSourceLabel

            TARGET_MESSAGE:
            $targetText

            PRIOR_CONTEXT_DIGESTS:
            ${chunkDigests.mapIndexed { index, digest -> "Digest ${index + 1}:\n$digest" }.joinToString("\n\n")}
        """.trimIndent()

        val response = runtime.callMemoryStageWithRetry(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(memoryCompactionTaskMessage(prompt)),
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.THREAD_CONTEXT_COMPACTION_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    toolContext = mapOf(
                        "memoryThreadContextCompactionMerge" to true,
                        "memoryThreadContextCompactionChunks" to chunkDigests.size,
                    ),
                ),
            ),
            stageName = "MemoryThreadContextCompactorMerge",
            logContext = "$logContext digests=${chunkDigests.size} promptChars=${prompt.length}",
        )

        return AiConversationMessageMapper.extractAssistantText(response).trim()
    }

    private fun memoryCompactionTaskMessage(prompt: String): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id("memory-context-compaction-actionItem"),
            conversationId = Conversation.Id("memory-context-compaction"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(prompt)),
            createdAt = Clock.System.now(),
        )

}

private const val PRIOR_CONTEXT_COMPACTION_THRESHOLD_CHARS = 60_000
private const val RAW_TAIL_CONTEXT_CHARS = 16_000
private const val MIN_RAW_TAIL_CONTEXT_CHARS = 2_000
private const val COMPACTION_CHUNK_CHARS = 32_000
private const val MIN_COMPACTION_CHUNK_CHARS = 2_000
private const val TARGET_CONTEXT_CHARS = 8_000
private const val MESSAGE_CONTEXT_CHARS = 6_000
private const val MEMORY_CONTEXT_CHARS_PER_TOKEN_ESTIMATE = 3
private const val MEMORY_STAGE_PROMPT_OVERHEAD_TOKENS = 4_000

private fun List<Conversation.Message>.takeLastWithinMemoryCompactionBudget(maxChars: Int): List<Conversation.Message> {
    var chars = 0
    val result = ArrayDeque<Conversation.Message>()
    for (message in asReversed()) {
        val messageChars = message.memoryCompactionCharCount()
        if (result.isNotEmpty() && chars + messageChars > maxChars) break
        result.addFirst(message)
        chars += messageChars
    }
    return result.toList()
}

private fun List<Conversation.Message>.chunkForMemoryCompaction(maxChars: Int): List<List<Conversation.Message>> {
    val chunks = mutableListOf<List<Conversation.Message>>()
    val current = mutableListOf<Conversation.Message>()
    var chars = 0

    for (message in this) {
        val messageChars = message.memoryCompactionCharCount()
        if (current.isNotEmpty() && chars + messageChars > maxChars) {
            chunks += current.toList()
            current.clear()
            chars = 0
        }
        current += message
        chars += messageChars
    }

    if (current.isNotEmpty()) {
        chunks += current.toList()
    }

    return chunks
}

private fun Conversation.Message.memoryCompactionCharCount(): Int =
    content.sumOf { item ->
        when (item) {
            is Conversation.Message.ContentItem.UserMessage -> item.text.length
            is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText.length
            is Conversation.Message.ContentItem.System -> item.content.length
            is Conversation.Message.ContentItem.Thinking -> 0
            is Conversation.Message.ContentItem.ToolCall -> item.call.name.length + item.call.input.toString().length
            is Conversation.Message.ContentItem.ToolResult -> item.result.sumOf { data ->
                when (data) {
                    is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.content.length
                    is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> 64
                    is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> data.url.length
                    is Conversation.Message.ContentItem.ToolResult.Data.FileData -> data.fileId.length
                }
            }
            is Conversation.Message.ContentItem.ImageItem -> 64
            is Conversation.Message.ContentItem.UnknownJson -> item.json.toString().length
        }
    }

private fun Conversation.Message.renderForMemoryCompaction(maxChars: Int): String {
    val body = content.mapNotNull { item ->
        when (item) {
            is Conversation.Message.ContentItem.UserMessage -> item.text
            is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
            is Conversation.Message.ContentItem.System -> "[system:${item.level.name}] ${item.content}"
            is Conversation.Message.ContentItem.Thinking -> null
            is Conversation.Message.ContentItem.ToolCall -> "[tool_call:${item.call.name}] ${item.call.input}"
            is Conversation.Message.ContentItem.ToolResult -> "[tool_result:${item.toolName}] " + item.result.joinToString("\n") { data ->
                when (data) {
                    is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.content.takeForMemoryCompaction(2_000)
                    is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> "[base64 ${data.mediaType.value}, ${data.data.length} chars]"
                    is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> "[url ${data.url}]"
                    is Conversation.Message.ContentItem.ToolResult.Data.FileData -> "[file ${data.fileId}]"
                }
            }
            is Conversation.Message.ContentItem.ImageItem -> "[image]"
            is Conversation.Message.ContentItem.UnknownJson -> "[unknown_json] ${item.json}"
        }
    }.joinToString("\n").trim()

    return "[${role.name} ${id.value}]\n${body.takeForMemoryCompaction(maxChars)}"
}

private fun String.takeForMemoryCompaction(maxChars: Int): String {
    if (length <= maxChars) return this
    return take(maxChars) + "\n...[truncated ${length - maxChars} chars]"
}

private fun Int.estimatedMemoryContextTokens(): Int =
    (this + MEMORY_CONTEXT_CHARS_PER_TOKEN_ESTIMATE - 1) / MEMORY_CONTEXT_CHARS_PER_TOKEN_ESTIMATE

private fun Int.toMemoryContextChars(reservedTokens: Int): Int =
    (this - reservedTokens).coerceAtLeast(1) * MEMORY_CONTEXT_CHARS_PER_TOKEN_ESTIMATE
