@file:OptIn(ExperimentalTime::class)

package com.gromozeka.bot.services

import com.gromozeka.bot.model.ClaudeLogEntry
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.message.ClaudeCodeToolCallData
import com.gromozeka.shared.domain.message.ToolCallData
import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.time.ExperimentalTime

/**
 * Maps ClaudeLogEntry to ChatMessage
 */
object ClaudeLogEntryMapper {

    fun mapToChatMessage(entry: ClaudeLogEntry): ChatMessage? {
        return when (entry) {
            is ClaudeLogEntry.UserEntry -> mapUserEntry(entry)
            is ClaudeLogEntry.AssistantEntry -> mapAssistantEntry(entry)
            is ClaudeLogEntry.SystemEntry -> mapSystemEntry(entry)
            is ClaudeLogEntry.SummaryEntry -> null // Skip summaries for now
        }
    }

    private fun mapUserEntry(entry: ClaudeLogEntry.UserEntry): ChatMessage {
        val content = mutableListOf<ChatMessage.ContentItem>()

        // Add message content if present
        entry.message?.let { message ->
            val messageContent = message.toContentItems()
            content.addAll(messageContent)
        }

        // Add tool use result if present
        entry.toolUseResult?.let { toolResultJson ->
            val toolResults = extractToolResults(toolResultJson)
            content.addAll(toolResults)
        }

        // If no content, add empty message
        if (content.isEmpty()) {
            content.add(ChatMessage.ContentItem.UserMessage(""))
        }

        return ChatMessage(
            uuid = entry.uuid,
            parentUuid = entry.parentUuid,
            role = ChatMessage.Role.USER,
            content = content,
            timestamp = Instant.parse(entry.timestamp),
            llmSpecificMetadata = ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry(
                sessionId = entry.sessionId,
                version = entry.version,
                userType = entry.userType,
                isSidechain = entry.isSidechain ?: false,
                isCompactSummary = entry.isCompactSummary ?: false,
                isMeta = entry.isMeta ?: false
            ),
            gitBranch = entry.gitBranch,
            cwd = entry.cwd
        )
    }

    private fun mapAssistantEntry(entry: ClaudeLogEntry.AssistantEntry): ChatMessage {
        val content = mutableListOf<ChatMessage.ContentItem>()

        // Add message content if present
        entry.message?.let { message ->
            val messageContent = message.toContentItems()
            content.addAll(messageContent)
        }

        // If no content, add empty message
        if (content.isEmpty()) {
            content.add(ChatMessage.ContentItem.UserMessage(""))
        }

        // Extract usage info from message content
        val usageInfo = when (val message = entry.message) {
            is ClaudeLogEntry.Message.AssistantMessage -> {
                val usage = message.usage
                val info = ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry.UsageInfo(
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    cacheCreationTokens = usage.cacheCreationInputTokens,
                    cacheReadTokens = usage.cacheReadInputTokens
                )
                info
            }

            else -> {
                null
            }
        }

        return ChatMessage(
            uuid = entry.uuid,
            parentUuid = entry.parentUuid,
            role = ChatMessage.Role.ASSISTANT,
            content = content,
            timestamp = Instant.parse(entry.timestamp),
            llmSpecificMetadata = ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry(
                sessionId = entry.sessionId,
                requestId = entry.requestId,
                version = entry.version,
                userType = entry.userType,
                isSidechain = entry.isSidechain ?: false,
                isMeta = false,
                usage = usageInfo
            ),
            gitBranch = entry.gitBranch,
            cwd = entry.cwd
        )
    }

    private fun mapSystemEntry(entry: ClaudeLogEntry.SystemEntry): ChatMessage {
        val systemLevel = when (entry.level) {
            "info" -> ChatMessage.ContentItem.System.SystemLevel.INFO
            "warning" -> ChatMessage.ContentItem.System.SystemLevel.WARNING
            "error" -> ChatMessage.ContentItem.System.SystemLevel.ERROR
            else -> ChatMessage.ContentItem.System.SystemLevel.INFO
        }

        val content = listOf(
            ChatMessage.ContentItem.System(
                level = systemLevel,
                content = entry.content,
                toolUseId = entry.toolUseId
            )
        )

        return ChatMessage(
            uuid = entry.uuid,
            parentUuid = entry.parentUuid,
            role = ChatMessage.Role.SYSTEM,
            content = content,
            timestamp = Instant.parse(entry.timestamp),
            llmSpecificMetadata = ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry(
                sessionId = entry.sessionId,
                version = entry.version,
                userType = entry.userType,
                isSidechain = entry.isSidechain ?: false,
                isMeta = entry.isMeta
            ),
            gitBranch = entry.gitBranch,
            cwd = entry.cwd
        )
    }

    private fun ClaudeLogEntry.Message.toContentItems(): List<ChatMessage.ContentItem> {
        return when (this) {
            is ClaudeLogEntry.Message.UserMessage -> {
                extractContentFromClaudeMessageContent(this.content)
            }

            is ClaudeLogEntry.Message.AssistantMessage -> {
                this.content.flatMap { assistantContent ->
                    parseAssistantContent(assistantContent)
                }
            }
        }
    }

    private fun extractContentFromClaudeMessageContent(content: ClaudeLogEntry.Message.UserMessage.Content): List<ChatMessage.ContentItem> {
        return when (content) {
            is ClaudeLogEntry.Message.UserMessage.Content.StringContent -> {
                listOf(ChatMessage.ContentItem.UserMessage(content.content))
            }

            is ClaudeLogEntry.Message.UserMessage.Content.ArrayContent -> {
                content.content.flatMap { userContentItem ->
                    parseUserContentItem(userContentItem)
                }
            }

            is ClaudeLogEntry.Message.UserMessage.Content.GromozekaJsonContent -> {
                // Deserialize JsonObject directly into StructuredText
                val structured = Json.decodeFromJsonElement<ChatMessage.StructuredText>(content.data)
                listOf(
                    ChatMessage.ContentItem.AssistantMessage(
                        structured = structured
                    )
                )
            }

            is ClaudeLogEntry.Message.UserMessage.Content.UnknownJsonContent -> {
                listOf(ChatMessage.ContentItem.UnknownJson(content.json))
            }
        }
    }

    private fun parseUserContentItem(item: ClaudeLogEntry.UserContentItem): List<ChatMessage.ContentItem> {
        return when (item) {
            is ClaudeLogEntry.UserContentItem.TextItem -> {
                listOf(ChatMessage.ContentItem.UserMessage(item.text))
            }

            is ClaudeLogEntry.UserContentItem.ImageItem -> {
                val chatImageSource = when (val source = item.source) {
                    is ClaudeLogEntry.ImageSource.Base64ImageSource -> {
                        ChatMessage.ImageSource.Base64ImageSource(
                            data = source.data,
                            mediaType = source.mediaType
                        )
                    }

                    is ClaudeLogEntry.ImageSource.UrlImageSource -> {
                        ChatMessage.ImageSource.UrlImageSource(
                            url = source.url
                        )
                    }

                    is ClaudeLogEntry.ImageSource.FileImageSource -> {
                        ChatMessage.ImageSource.FileImageSource(
                            fileId = source.fileId
                        )
                    }
                }
                listOf(ChatMessage.ContentItem.ImageItem(chatImageSource))
            }

            is ClaudeLogEntry.UserContentItem.ToolResultItem -> {
                val resultData = when (val typedContent = item.getTypedContent()) {
                    is ClaudeLogEntry.ToolResultContent.StringToolResult -> {
                        listOf(ChatMessage.ContentItem.ToolResult.Data.Text(typedContent.content))
                    }

                    is ClaudeLogEntry.ToolResultContent.ArrayToolResult -> {
                        val textContent = typedContent.content.joinToString("\n") { it.text }
                        listOf(ChatMessage.ContentItem.ToolResult.Data.Text(textContent))
                    }

                    is ClaudeLogEntry.ToolResultContent.MixedContentToolResult -> {
                        val dataItems = mutableListOf<ChatMessage.ContentItem.ToolResult.Data>()
                        typedContent.content.forEach { contentItem ->
                            when (contentItem) {
                                is ClaudeLogEntry.UserContentItem.TextItem -> {
                                    dataItems.add(ChatMessage.ContentItem.ToolResult.Data.Text(contentItem.text))
                                }

                                is ClaudeLogEntry.UserContentItem.ImageItem -> {
                                    when (val source = contentItem.source) {
                                        is ClaudeLogEntry.ImageSource.Base64ImageSource -> {
                                            dataItems.add(
                                                ChatMessage.ContentItem.ToolResult.Data.Base64Data(
                                                    data = source.data,
                                                    mediaType = ChatMessage.MediaType.parse(source.mediaType)
                                                )
                                            )
                                        }

                                        is ClaudeLogEntry.ImageSource.UrlImageSource -> {
                                            dataItems.add(
                                                ChatMessage.ContentItem.ToolResult.Data.UrlData(
                                                    url = source.url
                                                )
                                            )
                                        }

                                        is ClaudeLogEntry.ImageSource.FileImageSource -> {
                                            dataItems.add(
                                                ChatMessage.ContentItem.ToolResult.Data.FileData(
                                                    fileId = source.fileId
                                                )
                                            )
                                        }
                                    }
                                }

                                else -> {
                                    dataItems.add(ChatMessage.ContentItem.ToolResult.Data.Text("[Unknown content type: ${contentItem.type}]"))
                                }
                            }
                        }
                        dataItems
                    }
                }
                listOf(
                    ChatMessage.ContentItem.ToolResult(
                        toolUseId = item.toolUseId,
                        result = resultData,
                        isError = false
                    )
                )
            }
        }
    }


    private fun parseAssistantContent(content: ClaudeLogEntry.AssistantContent): List<ChatMessage.ContentItem> {
        return when (content) {
            is ClaudeLogEntry.AssistantContent.TextContent -> {
                // Check if text contains Gromozeka JSON
                parseTextContentForGromozeka(content.text)
            }

            is ClaudeLogEntry.AssistantContent.ToolUseContent -> {
                val toolCall = mapToolCall(content.name, content.input)
                listOf(ChatMessage.ContentItem.ToolCall(content.id, toolCall))
            }

            is ClaudeLogEntry.AssistantContent.ThinkingContent -> {
                listOf(ChatMessage.ContentItem.Thinking(content.signature, content.thinking))
            }
        }
    }

    private fun extractMessageContent(messageJson: JsonElement): List<ChatMessage.ContentItem> {
        val content = mutableListOf<ChatMessage.ContentItem>()

        when (messageJson) {
            is JsonArray -> {
                messageJson.forEach { item ->
                    content.addAll(parseMessageItem(item))
                }
            }

            is JsonObject -> {
                content.addAll(parseMessageItem(messageJson))
            }

            is JsonPrimitive -> {
                // Simple text message
                content.add(ChatMessage.ContentItem.UserMessage(messageJson.content))
            }

            else -> {
                // Unknown format, add as text
                content.add(ChatMessage.ContentItem.UserMessage(messageJson.toString()))
            }
        }

        return content
    }

    private fun parseMessageItem(item: JsonElement): List<ChatMessage.ContentItem> {
        if (item !is JsonObject) {
            return listOf(ChatMessage.ContentItem.UserMessage(item.toString()))
        }

        val type = item["type"]?.jsonPrimitive?.content

        return when (type) {
            "text" -> {
                val text = item["text"]?.jsonPrimitive?.content ?: ""
                listOf(ChatMessage.ContentItem.UserMessage(text))
            }

            "tool_use" -> {
                val id = item["id"]?.jsonPrimitive?.content ?: ""
                val name = item["name"]?.jsonPrimitive?.content ?: ""
                val input = item["input"] ?: JsonObject(emptyMap())

                val toolCall = mapToolCall(name, input)
                listOf(ChatMessage.ContentItem.ToolCall(id, toolCall))
            }

            "thinking" -> {
                val signature = item["signature"]?.jsonPrimitive?.content ?: ""
                val thinking = item["thinking"]?.jsonPrimitive?.content ?: ""
                listOf(ChatMessage.ContentItem.Thinking(signature, thinking))
            }

            else -> {
                // Unknown type, add as text
                listOf(ChatMessage.ContentItem.UserMessage(item.toString()))
            }
        }
    }

    private fun extractToolResults(toolResultJson: JsonElement): List<ChatMessage.ContentItem> {
        val results = mutableListOf<ChatMessage.ContentItem>()

        when (toolResultJson) {
            is JsonArray -> {
                toolResultJson.forEach { resultItem ->
                    results.addAll(parseToolResult(resultItem))
                }
            }

            is JsonObject -> {
                results.addAll(parseToolResult(toolResultJson))
            }

            else -> {
                // Fallback: treat as generic result
                results.add(
                    ChatMessage.ContentItem.ToolResult(
                        toolUseId = "",
                        result = listOf(ChatMessage.ContentItem.ToolResult.Data.Text("Unknown tool result: $toolResultJson")),
                        isError = false
                    )
                )
            }
        }

        return results
    }

    private fun parseToolResult(resultJson: JsonElement): List<ChatMessage.ContentItem> {
        if (resultJson !is JsonObject) {
            return listOf(
                ChatMessage.ContentItem.ToolResult(
                    toolUseId = "",
                    result = listOf(ChatMessage.ContentItem.ToolResult.Data.Text("Unknown tool result: $resultJson")),
                    isError = false
                )
            )
        }

        val toolUseId = resultJson["toolUseId"]?.jsonPrimitive?.content ?: ""
        val isError = resultJson["isError"]?.jsonPrimitive?.boolean ?: false
        val content = resultJson["content"]

        // Try to determine tool type from content structure
        val resultData = when {
            content is JsonObject && content.containsKey("stdout") -> {
                // Bash tool result - combine stdout/stderr
                val stdout = content["stdout"]?.jsonPrimitive?.content ?: ""
                val stderr = content["stderr"]?.jsonPrimitive?.content ?: ""
                val combined = listOfNotNull(
                    if (stdout.isNotBlank()) "STDOUT:\n$stdout" else null,
                    if (stderr.isNotBlank()) "STDERR:\n$stderr" else null
                ).joinToString("\n\n")
                listOf(ChatMessage.ContentItem.ToolResult.Data.Text(combined.ifBlank { "No output" }))
            }

            content is JsonPrimitive -> {
                // Simple text result (likely Read)
                listOf(ChatMessage.ContentItem.ToolResult.Data.Text(content.content))
            }

            content is JsonArray -> {
                // Array result - join text elements
                val textContent = content.joinToString("\n") { element ->
                    when (element) {
                        is JsonPrimitive -> element.content
                        is JsonObject -> element["text"]?.jsonPrimitive?.content ?: element.toString()
                        else -> element.toString()
                    }
                }
                listOf(ChatMessage.ContentItem.ToolResult.Data.Text(textContent))
            }

            else -> {
                // Generic result
                listOf(ChatMessage.ContentItem.ToolResult.Data.Text("Unknown content: ${content ?: JsonNull}"))
            }
        }

        return listOf(
            ChatMessage.ContentItem.ToolResult(
                toolUseId = toolUseId,
                result = resultData,
                isError = isError
            )
        )
    }

    private fun mapToolCall(name: String, input: JsonElement): ToolCallData {
        return when (name) {
            "Read" -> {
                val filePath = input.jsonObject["file_path"]?.jsonPrimitive?.content ?: ""
                val offset = input.jsonObject["offset"]?.jsonPrimitive?.int
                val limit = input.jsonObject["limit"]?.jsonPrimitive?.int
                ClaudeCodeToolCallData.Read(filePath, offset, limit)
            }

            "Edit" -> {
                val filePath = input.jsonObject["file_path"]?.jsonPrimitive?.content ?: ""
                val oldString = input.jsonObject["old_string"]?.jsonPrimitive?.content ?: ""
                val newString = input.jsonObject["new_string"]?.jsonPrimitive?.content ?: ""
                val replaceAll = input.jsonObject["replace_all"]?.jsonPrimitive?.boolean ?: false
                ClaudeCodeToolCallData.Edit(filePath, oldString, newString, replaceAll)
            }

            "Bash" -> {
                val command = input.jsonObject["command"]?.jsonPrimitive?.content ?: ""
                val description = input.jsonObject["description"]?.jsonPrimitive?.content
                val timeout = input.jsonObject["timeout"]?.jsonPrimitive?.int
                ClaudeCodeToolCallData.Bash(command, description, timeout)
            }

            "Grep" -> {
                val pattern = input.jsonObject["pattern"]?.jsonPrimitive?.content ?: ""
                val path = input.jsonObject["path"]?.jsonPrimitive?.content
                val glob = input.jsonObject["glob"]?.jsonPrimitive?.content
                val outputMode = input.jsonObject["output_mode"]?.jsonPrimitive?.content
                val caseInsensitive = input.jsonObject["-i"]?.jsonPrimitive?.boolean
                val multiline = input.jsonObject["multiline"]?.jsonPrimitive?.boolean
                ClaudeCodeToolCallData.Grep(pattern, path, glob, outputMode, caseInsensitive, multiline)
            }

            "TodoWrite" -> {
                ClaudeCodeToolCallData.TodoWrite(input)
            }

            "WebSearch" -> {
                val query = input.jsonObject["query"]?.jsonPrimitive?.content ?: ""
                val allowedDomains = input.jsonObject["allowed_domains"]?.jsonArray?.map { it.jsonPrimitive.content }
                val blockedDomains = input.jsonObject["blocked_domains"]?.jsonArray?.map { it.jsonPrimitive.content }
                ClaudeCodeToolCallData.WebSearch(query, allowedDomains, blockedDomains)
            }

            "WebFetch" -> {
                val url = input.jsonObject["url"]?.jsonPrimitive?.content ?: ""
                val prompt = input.jsonObject["prompt"]?.jsonPrimitive?.content ?: ""
                ClaudeCodeToolCallData.WebFetch(url, prompt)
            }

            "Task" -> {
                val description = input.jsonObject["description"]?.jsonPrimitive?.content ?: ""
                val prompt = input.jsonObject["prompt"]?.jsonPrimitive?.content ?: ""
                val subagentType = input.jsonObject["subagent_type"]?.jsonPrimitive?.content ?: ""
                ClaudeCodeToolCallData.Task(description, prompt, subagentType)
            }

            else -> {
                // Unknown tool, use generic
                ToolCallData.Generic(name, input)
            }
        }
    }

    private fun parseTextContentForGromozeka(text: String): List<ChatMessage.ContentItem> {
        // Quick check - looks like JSON?
        if (!text.trim().startsWith("{") || !text.trim().endsWith("}")) {
            return listOf(ChatMessage.ContentItem.UserMessage(text))
        }

        return try {
            // Try to deserialize as StructuredText first
            val structured = Json.decodeFromString<ChatMessage.StructuredText>(text)
            listOf(
                ChatMessage.ContentItem.AssistantMessage(
                    structured = structured
                )
            )
        } catch (e: SerializationException) {
            // Not a Gromozeka format, try as generic JSON
            try {
                val jsonElement = Json.parseToJsonElement(text)
                listOf(ChatMessage.ContentItem.UnknownJson(jsonElement))
            } catch (jsonParseException: Exception) {
                // Not valid JSON at all - treat as plain text
                listOf(ChatMessage.ContentItem.UserMessage(text))
            }
        } catch (e: Exception) {
            // Any other error - treat as plain text
            listOf(ChatMessage.ContentItem.UserMessage(text))
        }
    }
}