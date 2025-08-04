package com.gromozeka.bot.services

import com.gromozeka.bot.model.ClaudeLogEntry
import com.gromozeka.bot.model.GromozekaJson
import com.gromozeka.shared.domain.message.*
import kotlinx.datetime.Instant
import kotlinx.serialization.*
import kotlinx.serialization.json.*

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
            val messageContent = extractMessageFromMessage(message)
            content.addAll(messageContent)
        }
        
        // Add tool use result if present
        entry.toolUseResult?.let { toolResultJson ->
            val toolResults = extractToolResults(toolResultJson)
            content.addAll(toolResults)
        }
        
        // If no content, add empty message
        if (content.isEmpty()) {
            content.add(ChatMessage.ContentItem.Message(""))
        }
        
        return ChatMessage(
            uuid = entry.uuid,
            parentUuid = entry.parentUuid,
            messageType = ChatMessage.MessageType.USER,
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
            val messageContent = extractMessageFromMessage(message)
            content.addAll(messageContent)
        }
        
        // If no content, add empty message
        if (content.isEmpty()) {
            content.add(ChatMessage.ContentItem.Message(""))
        }
        
        return ChatMessage(
            uuid = entry.uuid,
            parentUuid = entry.parentUuid,
            messageType = ChatMessage.MessageType.ASSISTANT,
            content = content,
            timestamp = Instant.parse(entry.timestamp),
            llmSpecificMetadata = ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry(
                sessionId = entry.sessionId,
                requestId = entry.requestId,
                version = entry.version,
                userType = entry.userType,
                isSidechain = entry.isSidechain ?: false,
                isMeta = false
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
            messageType = ChatMessage.MessageType.SYSTEM,
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
    
    private fun extractMessageFromMessage(message: ClaudeLogEntry.Message): List<ChatMessage.ContentItem> {
        return when (message) {
            is ClaudeLogEntry.Message.UserMessage -> {
                extractContentFromClaudeMessageContent(message.content)
            }
            is ClaudeLogEntry.Message.AssistantMessage -> {
                message.content.flatMap { assistantContent ->
                    parseAssistantContent(assistantContent)
                }
            }
        }
    }
    
    private fun extractContentFromClaudeMessageContent(content: ClaudeLogEntry.ClaudeMessageContent): List<ChatMessage.ContentItem> {
        return when (content) {
            is ClaudeLogEntry.ClaudeMessageContent.StringContent -> {
                listOf(ChatMessage.ContentItem.Message(content.content))
            }
            is ClaudeLogEntry.ClaudeMessageContent.ArrayContent -> {
                content.content.flatMap { userContentItem ->
                    parseUserContentItem(userContentItem)
                }
            }
            is ClaudeLogEntry.ClaudeMessageContent.GromozekaJsonContent -> {
                // Десериализуем JsonObject напрямую в GromozekaMessage
                val fullText = content.data["fullText"]?.jsonPrimitive?.content ?: ""
                val ttsText = content.data["ttsText"]?.jsonPrimitive?.content
                val voiceTone = content.data["voiceTone"]?.jsonPrimitive?.content
                listOf(ChatMessage.ContentItem.GromozekaMessage(
                    fullText = fullText,
                    ttsText = ttsText,
                    voiceTone = voiceTone
                ))
            }
            is ClaudeLogEntry.ClaudeMessageContent.UnknownJsonContent -> {
                listOf(ChatMessage.ContentItem.UnknownJson(content.json))
            }
        }
    }
    
    private fun parseUserContentItem(item: ClaudeLogEntry.UserContentItem): List<ChatMessage.ContentItem> {
        return when (item) {
            is ClaudeLogEntry.UserContentItem.TextItem -> {
                listOf(ChatMessage.ContentItem.Message(item.text))
            }
            is ClaudeLogEntry.UserContentItem.ToolResultItem -> {
                val toolResult = parseToolResultContent(item.content)
                listOf(ChatMessage.ContentItem.ToolResult(
                    toolUseId = item.toolUseId,
                    result = toolResult,
                    isError = false
                ))
            }
        }
    }
    
    private fun parseToolResultContent(content: ClaudeLogEntry.ToolResultContent): ToolResultData {
        return when (content) {
            is ClaudeLogEntry.ToolResultContent.StringToolResult -> {
                ClaudeCodeToolResultData.Read(content.content)
            }
            is ClaudeLogEntry.ToolResultContent.ArrayToolResult -> {
                val textContent = content.content.joinToString("\n") { it.text }
                ClaudeCodeToolResultData.Read(textContent)
            }
        }
    }
    
    private fun parseAssistantContent(content: ClaudeLogEntry.AssistantContent): List<ChatMessage.ContentItem> {
        return when (content) {
            is ClaudeLogEntry.AssistantContent.TextContent -> {
                // Проверяем, содержит ли текст JSON Громозеки
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
                content.add(ChatMessage.ContentItem.Message(messageJson.content))
            }
            else -> {
                // Unknown format, add as text
                content.add(ChatMessage.ContentItem.Message(messageJson.toString()))
            }
        }
        
        return content
    }
    
    private fun parseMessageItem(item: JsonElement): List<ChatMessage.ContentItem> {
        if (item !is JsonObject) {
            return listOf(ChatMessage.ContentItem.Message(item.toString()))
        }
        
        val type = item["type"]?.jsonPrimitive?.content
        
        return when (type) {
            "text" -> {
                val text = item["text"]?.jsonPrimitive?.content ?: ""
                listOf(ChatMessage.ContentItem.Message(text))
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
                listOf(ChatMessage.ContentItem.Message(item.toString()))
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
                        result = ToolResultData.Generic("unknown", toolResultJson),
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
                    result = ToolResultData.Generic("unknown", resultJson),
                    isError = false
                )
            )
        }
        
        val toolUseId = resultJson["toolUseId"]?.jsonPrimitive?.content ?: ""
        val isError = resultJson["isError"]?.jsonPrimitive?.boolean ?: false
        val content = resultJson["content"]
        
        // Try to determine tool type from content structure
        val result = when {
            content is JsonObject && content.containsKey("stdout") -> {
                // Bash result
                val stdout = content["stdout"]?.jsonPrimitive?.content
                val stderr = content["stderr"]?.jsonPrimitive?.content
                val interrupted = content["interrupted"]?.jsonPrimitive?.boolean ?: false
                val isImage = content["isImage"]?.jsonPrimitive?.boolean ?: false
                
                ClaudeCodeToolResultData.Bash(stdout, stderr, interrupted, isImage)
            }
            
            content is JsonPrimitive -> {
                // Simple text result (likely Read)
                ClaudeCodeToolResultData.Read(content.content)
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
                ClaudeCodeToolResultData.Read(textContent)
            }
            
            else -> {
                // Generic result
                ToolResultData.Generic("unknown", content ?: JsonNull)
            }
        }
        
        return listOf(
            ChatMessage.ContentItem.ToolResult(
                toolUseId = toolUseId,
                result = result,
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
        // Быстрая проверка - похоже на JSON?
        if (!text.trim().startsWith("{") || !text.trim().endsWith("}")) {
            return listOf(ChatMessage.ContentItem.Message(text))
        }
        
        return try {
            // Try to deserialize as GromozekaJson first
            val gromozekaData = Json.decodeFromString<GromozekaJson>(text)
            listOf(ChatMessage.ContentItem.GromozekaMessage(
                fullText = gromozekaData.fullText,
                ttsText = gromozekaData.ttsText,
                voiceTone = gromozekaData.voiceTone
            ))
        } catch (e: SerializationException) {
            // Not a Gromozeka format, try as generic JSON
            try {
                val jsonElement = Json.parseToJsonElement(text)
                listOf(ChatMessage.ContentItem.UnknownJson(jsonElement))
            } catch (e: Exception) {
                println("[ClaudeLogEntryMapper] Failed to parse text as JSON: ${e.message}")
                println("  Text: ${text.take(200)}${if (text.length > 200) "..." else ""}")
                // Not valid JSON at all - treat as plain text
                listOf(ChatMessage.ContentItem.Message(text))
            }
        } catch (e: Exception) {
            // Any other error - treat as plain text
            listOf(ChatMessage.ContentItem.Message(text))
        }
    }
}