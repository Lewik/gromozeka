package com.gromozeka.bot.services.llm.claudecode.converter

import com.gromozeka.bot.model.*
import com.gromozeka.bot.parsers.ResponseParserFactory
import com.gromozeka.bot.services.SettingsService
import com.gromozeka.bot.settings.ResponseFormat
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.message.ClaudeCodeToolCallData
import com.gromozeka.shared.domain.message.ToolCallData
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import com.gromozeka.shared.domain.session.toClaudeSessionUuid
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.*
import kotlin.time.Clock

/**
 * Converts ClaudeCodeStreamJsonLine to ChatMessage for UI consumption
 */
@Service
class ClaudeStreamToChatConverter(
    private val settingsService: SettingsService,
    @Qualifier("coroutineScope") private val scope: CoroutineScope,
) {

    // Current response format - updated from settings
    private var currentResponseFormat: ResponseFormat = ResponseFormat.JSON

    @PostConstruct
    fun init() {
        // Subscribe to settings changes
        scope.launch {
            settingsService.settingsFlow.collect { settings ->
                currentResponseFormat = settings.responseFormat
            }
        }
    }

    fun mapToChatMessage(streamMessage: ClaudeCodeStreamJsonLine): ChatMessage {
        return when (streamMessage) {
            is ClaudeCodeStreamJsonLine.System -> mapSystemMessage(streamMessage)
            is ClaudeCodeStreamJsonLine.User -> mapUserMessage(streamMessage)
            is ClaudeCodeStreamJsonLine.Assistant -> mapAssistantMessage(streamMessage)
            is ClaudeCodeStreamJsonLine.Result -> mapResultMessage(streamMessage)
            is ClaudeCodeStreamJsonLine.ControlRequest -> mapControlRequestMessage(streamMessage)
            is ClaudeCodeStreamJsonLine.ControlResponse -> mapControlResponseMessage(streamMessage)
        }
    }

    private fun mapSystemMessage(message: ClaudeCodeStreamJsonLine.System): ChatMessage {
        val level = when (message.subtype) {
            "error" -> ChatMessage.ContentItem.System.SystemLevel.ERROR
            "warning" -> ChatMessage.ContentItem.System.SystemLevel.WARNING
            else -> ChatMessage.ContentItem.System.SystemLevel.INFO //todo unknown level
        }

        val content = listOf(
            ChatMessage.ContentItem.System(
                level = level,
                content = "${message.subtype}: ${message.data}",
                toolUseId = null
            )
        )

        return ChatMessage(
            uuid = generateUuid(),
            parentUuid = null,
            role = ChatMessage.Role.SYSTEM,
            content = content,
            timestamp = Clock.System.now(),
            llmSpecificMetadata = createStreamMetadata(
                sessionId = message.sessionId ?: "unknown".toClaudeSessionUuid()
            )
        )
    }

    private fun mapUserMessage(message: ClaudeCodeStreamJsonLine.User): ChatMessage {
        val (content, instructions, sender) = when (message.message.content) {
            is ContentItemsUnion.StringContent -> {
                parseUserMessageWithTags(message.message.content.content)
            }

            is ContentItemsUnion.ArrayContent -> {
                // For array content, don't parse tags (assume they're already processed)
                val contentItems = message.message.content.items.map { mapUserContentItem(it) }
                Triple(contentItems, emptyList<ChatMessage.Instruction>(), null)
            }
        }

        return ChatMessage(
            uuid = generateUuid(),
            parentUuid = null,
            role = ChatMessage.Role.USER,
            content = content,
            timestamp = Clock.System.now(),
            llmSpecificMetadata = createStreamMetadata(
                sessionId = message.sessionId
            ),
            instructions = instructions,
            sender = sender
        )
    }

    private fun mapAssistantMessage(message: ClaudeCodeStreamJsonLine.Assistant): ChatMessage {
        val contentItems = message.message.content.flatMap { item ->
            listOf(mapAssistantContentItem(item))
        }

        return ChatMessage(
            uuid = generateUuid(),
            parentUuid = null,
            role = ChatMessage.Role.ASSISTANT,
            content = contentItems,
            timestamp = Clock.System.now(),
            llmSpecificMetadata = createStreamMetadata(
                sessionId = message.sessionId,
                model = message.message.model,
                usage = message.message.usage,
                stopReason = message.message.stopReason
            )
        )
    }

    private fun mapResultMessage(message: ClaudeCodeStreamJsonLine.Result): ChatMessage {
        val level = if (message.isError) {
            ChatMessage.ContentItem.System.SystemLevel.ERROR
        } else {
            ChatMessage.ContentItem.System.SystemLevel.INFO
        }

        // Parse result content based on subtype
        val content = when (message.subtype) {
            "success" -> {
                // Try to parse result as Gromozeka JSON if present
                if (message.result != null) {
                    parseResultAsGromozeka(message.result)
                } else {
                    // No result content, show statistics
                    listOf(createResultStatisticsContent(message, level))
                }
            }

            "error" -> {
                // Show error message
                val errorText = message.result ?: "Unknown error occurred"
                listOf(
                    ChatMessage.ContentItem.System(
                        level = ChatMessage.ContentItem.System.SystemLevel.ERROR,
                        content = "Error: $errorText",
                        toolUseId = null
                    )
                )
            }

            else -> {
                // Other subtypes (error_max_turns, error_during_execution, etc.)
                val resultText = buildString {
                    append("Result (${message.subtype}): ")
                    if (message.result != null) {
                        append(message.result)
                    } else {
                        append("${message.numTurns} turns, ")
                        append("${message.durationMs}ms total, ")
                        message.totalCostUsd?.let { append("$%.6f, ".format(it)) }
                        message.usage?.let {
                            append("${it.inputTokens} in / ${it.outputTokens} out tokens")
                        }
                    }
                }
                listOf(
                    ChatMessage.ContentItem.System(
                        level = level,
                        content = resultText,
                        toolUseId = null
                    )
                )
            }
        }

        return ChatMessage(
            uuid = generateUuid(),
            parentUuid = null,
            role = ChatMessage.Role.SYSTEM,
            content = content,
            timestamp = Clock.System.now(),
            llmSpecificMetadata = createStreamMetadata(
                sessionId = message.sessionId,
                usage = message.usage
            )
        )
    }


    private fun mapUserContentItem(item: ContentBlock) = when (item) {
        is ContentBlock.TextBlock -> parseText(item.text)

        is ContentBlock.ToolResultBlock -> ChatMessage.ContentItem.ToolResult(
            toolUseId = item.toolUseId,
            result = mapToolResult(item.content),
            isError = item.isError ?: false
        )

        is ContentBlock.ImageBlock -> {
            val chatImageSource = when (val source = item.source) {
                is ImageSource.Base64ImageSource -> {
                    ChatMessage.ImageSource.Base64ImageSource(
                        data = source.data,
                        mediaType = source.mediaType
                    )
                }

                is ImageSource.UrlImageSource -> {
                    ChatMessage.ImageSource.UrlImageSource(
                        url = source.url
                    )
                }

                is ImageSource.FileImageSource -> {
                    ChatMessage.ImageSource.FileImageSource(
                        fileId = source.fileId
                    )
                }
            }
            ChatMessage.ContentItem.ImageItem(chatImageSource)
        }

        is ContentBlock.ToolUseBlock -> error("USER messages cannot contain ToolUseBlock - only ASSISTANT can call tools")
        is ContentBlock.ThinkingItem -> error("USER messages cannot contain ThinkingItem - only ASSISTANT can have thinking")
    }

    private fun mapAssistantContentItem(item: ContentBlock) = when (item) {
        is ContentBlock.TextBlock -> parseTextForAssistant(item.text)

        is ContentBlock.ToolUseBlock -> ChatMessage.ContentItem.ToolCall(
            id = item.id,
            call = mapToolCall(item.name, item.input)
        )

        is ContentBlock.ThinkingItem -> ChatMessage.ContentItem.Thinking(
            signature = item.signature ?: "",
            thinking = item.thinking
        )

        is ContentBlock.ImageBlock -> {
            val chatImageSource = when (val source = item.source) {
                is ImageSource.Base64ImageSource -> {
                    ChatMessage.ImageSource.Base64ImageSource(
                        data = source.data,
                        mediaType = source.mediaType
                    )
                }

                is ImageSource.UrlImageSource -> {
                    ChatMessage.ImageSource.UrlImageSource(
                        url = source.url
                    )
                }

                is ImageSource.FileImageSource -> {
                    ChatMessage.ImageSource.FileImageSource(
                        fileId = source.fileId
                    )
                }
            }
            ChatMessage.ContentItem.ImageItem(chatImageSource)
        }

        is ContentBlock.ToolResultBlock -> error("ASSISTANT messages cannot contain ToolResultBlock - only USER can provide tool results")
    }

    private fun mapToolResult(content: ContentResultUnion?): List<ChatMessage.ContentItem.ToolResult.Data> =
        when (content) {
            null -> listOf(ChatMessage.ContentItem.ToolResult.Data.Text("No result")) // Special case for null content

            is ContentResultUnion.StringResult -> {
                listOf(ChatMessage.ContentItem.ToolResult.Data.Text(content.content))
            }

            is ContentResultUnion.ArrayResult -> {
                val dataItems = mutableListOf<ChatMessage.ContentItem.ToolResult.Data>()
                content.items.forEach { item ->
                    when (item) {
                        is ContentBlock.TextBlock -> {
                            dataItems.add(ChatMessage.ContentItem.ToolResult.Data.Text(item.text))
                        }

                        is ContentBlock.ImageBlock -> {
                            when (val source = item.source) {
                                is ImageSource.Base64ImageSource -> {
                                    dataItems.add(
                                        ChatMessage.ContentItem.ToolResult.Data.Base64Data(
                                            data = source.data,
                                            mediaType = ChatMessage.MediaType.parse(source.mediaType)
                                        )
                                    )
                                }

                                is ImageSource.UrlImageSource -> {
                                    dataItems.add(
                                        ChatMessage.ContentItem.ToolResult.Data.UrlData(
                                            url = source.url
                                        )
                                    )
                                }

                                is ImageSource.FileImageSource -> {
                                    dataItems.add(
                                        ChatMessage.ContentItem.ToolResult.Data.FileData(
                                            fileId = source.fileId
                                        )
                                    )
                                }
                            }
                        }

                        else -> {
                            dataItems.add(ChatMessage.ContentItem.ToolResult.Data.Text("[Unknown content type: ${item::class.simpleName}]"))
                        }
                    }
                }
                dataItems
            }
        }

    private fun mapToolCall(name: String, input: JsonElement): ToolCallData {
        return try {
            // For polymorphic deserialization, we could add a type field to the input
            // But since ClaudeCodeToolCallData uses different field names per type,
            // and the discriminator comes from outside (name parameter),
            // it's cleaner to use explicit mapping
            when (name) {
                "Read" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.Read>(input)
                "Edit" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.Edit>(input)
                "Bash" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.Bash>(input)
                "Grep" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.Grep>(input)
                "TodoWrite" -> ClaudeCodeToolCallData.TodoWrite(input) // Keep as-is since it stores raw JSON
                "WebSearch" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.WebSearch>(input)
                "WebFetch" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.WebFetch>(input)
                "Task" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.Task>(input)
                else -> ToolCallData.Generic(name, input)
            }
        } catch (e: Exception) {
            // Fallback to generic if deserialization fails
            println("[ClaudeStreamToChatConverter] TOOL CALL PARSE ERROR: Failed to deserialize tool call '$name'")
            println("  Exception: ${e.javaClass.simpleName}: ${e.message}")
            println("  Input JSON: $input")
            println("  Stack trace: ${e.stackTraceToString()}")
            ToolCallData.Generic(name, input)
        }
    }

    private fun parseText(text: String): ChatMessage.ContentItem {
        return try {
            val jsonElement = Json.parseToJsonElement(text)

            try {
                val structured = Json.decodeFromJsonElement<ChatMessage.StructuredText>(jsonElement)
                ChatMessage.ContentItem.AssistantMessage(structured = structured)
            } catch (_: SerializationException) {
                // Not a Gromozeka format, return as generic JSON
                println("[ClaudeStreamToChatConverter] PARSE: Text is not Gromozeka JSON, fallback to UnknownJson")
                println("  Text preview: ${text.take(100)}${if (text.length > 100) "..." else ""}")
                ChatMessage.ContentItem.UnknownJson(jsonElement)
            }
        } catch (_: Exception) {
            ChatMessage.ContentItem.UserMessage(text)
        }
    }

    private fun parseTextForAssistant(text: String): ChatMessage.ContentItem {
        return try {
            // Try to parse using the configured parser
            val parser = ResponseParserFactory.getParser(currentResponseFormat)
            val parsed = parser.parse(text)
            ChatMessage.ContentItem.AssistantMessage(structured = parsed)
        } catch (e: Exception) {
            // Parser failed - log and fallback to raw text
            println("[ClaudeStreamToChatConverter] Parser failed for format $currentResponseFormat: ${e.message}")
            println("  Text: ${text.take(100)}${if (text.length > 100) "..." else ""}")

            // Return as raw text fallback
            ChatMessage.ContentItem.AssistantMessage(
                structured = ChatMessage.StructuredText(
                    fullText = text,
                    ttsText = null,
                    voiceTone = null,
                    failedToParse = true
                )
            )
        }
    }

    private fun createStreamMetadata(
        sessionId: ClaudeSessionUuid,
        model: String? = null,
        usage: UsageInfo? = null,
        stopReason: String? = null,
    ): ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry {
        return ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry(
            sessionId = sessionId,
            model = model,
            usage = usage?.let { mapUsageInfo(it) },
            stopReason = stopReason,
            version = null,
            userType = null,
            isSidechain = false,
            isCompactSummary = false,
            isMeta = false
        )
    }

    private fun mapUsageInfo(usage: UsageInfo): ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry.UsageInfo {
        return ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry.UsageInfo(
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cacheCreationTokens = usage.cacheCreationInputTokens,
            cacheReadTokens = usage.cacheReadInputTokens
        )
    }

    private fun parseResultAsGromozeka(resultText: String): List<ChatMessage.ContentItem> {
        return try {
            // Parse JSON once
            val jsonElement = Json.parseToJsonElement(resultText)

            try {
                val structured = Json.decodeFromJsonElement<ChatMessage.StructuredText>(jsonElement)
                listOf(
                    ChatMessage.ContentItem.System(
                        level = ChatMessage.ContentItem.System.SystemLevel.INFO,
                        content = structured.fullText,
                        toolUseId = null
                    )
                )
            } catch (_: SerializationException) {
                // Not a Gromozeka format, return as generic JSON
                listOf(ChatMessage.ContentItem.UnknownJson(jsonElement))
            }
        } catch (_: Exception) {
            // Not valid JSON at all - treat as plain text
            listOf(ChatMessage.ContentItem.UserMessage(resultText))
        }
    }

    private fun createResultStatisticsContent(
        message: ClaudeCodeStreamJsonLine.Result,
        level: ChatMessage.ContentItem.System.SystemLevel,
    ): ChatMessage.ContentItem.System {
        val statisticsText = buildString {
            append("Result (${message.subtype}): ")
            append("${message.numTurns} turns, ")
            append("${message.durationMs}ms total, ")
            message.totalCostUsd?.let { append("$%.6f, ".format(it)) }
            message.usage?.let {
                append("${it.inputTokens} in / ${it.outputTokens} out tokens")
            }
        }

        return ChatMessage.ContentItem.System(
            level = level,
            content = statisticsText,
            toolUseId = null
        )
    }

    private fun mapControlRequestMessage(message: ClaudeCodeStreamJsonLine.ControlRequest): ChatMessage {
        val content = listOf(
            ChatMessage.ContentItem.System(
                level = ChatMessage.ContentItem.System.SystemLevel.INFO,
                content = "Control request sent: ${message.request.subtype} (ID: ${message.requestId})",
                toolUseId = null
            )
        )

        return ChatMessage(
            uuid = generateUuid(),
            parentUuid = null,
            role = ChatMessage.Role.SYSTEM,
            content = content,
            timestamp = Clock.System.now(),
            llmSpecificMetadata = createStreamMetadata(
                sessionId = "control".toClaudeSessionUuid()
            )
        )
    }

    private fun mapControlResponseMessage(message: ClaudeCodeStreamJsonLine.ControlResponse): ChatMessage {
        val level = when (message.response.subtype) {
            "error" -> ChatMessage.ContentItem.System.SystemLevel.ERROR
            else -> ChatMessage.ContentItem.System.SystemLevel.INFO
        }

        val responseText = when (message.response.subtype) {
            "success" -> "Control request acknowledged: Interrupt processed successfully"
            "error" -> "Control request error: ${message.response.error}"
            else -> "Control response: ${message.response.subtype}"
        }

        val content = listOf(
            ChatMessage.ContentItem.System(
                level = level,
                content = responseText,
                toolUseId = null
            )
        )

        return ChatMessage(
            uuid = generateUuid(),
            parentUuid = null,
            role = ChatMessage.Role.SYSTEM,
            content = content,
            timestamp = Clock.System.now(),
            llmSpecificMetadata = createStreamMetadata(
                sessionId = "control".toClaudeSessionUuid()
            )
        )
    }

    private fun generateUuid(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * Parse user message text to extract instructions, sender tags, and clean content
     */
    private fun parseUserMessageWithTags(text: String): Triple<List<ChatMessage.ContentItem>, List<ChatMessage.Instruction>, ChatMessage.Sender?> {
        var remainingText = text.trim()
        val instructions = mutableListOf<ChatMessage.Instruction>()
        var sender: ChatMessage.Sender? = null

        // Parse sender tag first
        val senderMatch = Regex("<message_source>(.*?)</message_source>").find(remainingText)
        senderMatch?.let { match ->
            val senderValue = match.groupValues[1]
            sender = when {
                senderValue == "user" -> ChatMessage.Sender.User
                senderValue.startsWith("tab:") -> ChatMessage.Sender.Tab(senderValue.removePrefix("tab:"))
                else -> null
            }
            remainingText = remainingText.replace(match.value, "").trim()
        }

        // Parse instruction tags
        val instructionRegex = Regex("<instruction>(.*?)</instruction>")
        var match = instructionRegex.find(remainingText)
        while (match != null) {
            val instructionContent = match.groupValues[1]
            val parts = instructionContent.split(":", limit = 3)
            if (parts.size == 3) {
                instructions.add(
                    ChatMessage.Instruction(
                        id = parts[0].trim(),
                        title = parts[1].trim(),
                        description = parts[2].trim()
                    )
                )
            }
            remainingText = remainingText.replace(match.value, "").trim()
            match = instructionRegex.find(remainingText)
        }

        // Create content from remaining text
        val content = if (remainingText.isNotEmpty()) {
            listOf(parseText(remainingText))
        } else {
            emptyList()
        }

        return Triple(content, instructions, sender)
    }
}