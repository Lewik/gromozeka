package com.gromozeka.bot.services

import com.gromozeka.bot.model.ChatMessage
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.model.ClaudeCodeSessionEntryV1_0
import com.gromozeka.bot.utils.decodeProjectPath
import java.io.File

class SessionMetadataExtractor {

    private val parser = ClaudeCodeSessionParser()
    private val mapper = ClaudeCodeSessionMapper

    fun extractMetadata(sessionFile: File): ChatSession? {
        val startTime = System.currentTimeMillis()
        try {
            val sessionId = sessionFile.name.removeSuffix(".jsonl")
            val lines = sessionFile.readLines()
            val readTime = System.currentTimeMillis()

            if (lines.isEmpty()) {
                println("[SessionMetadataExtractor] $sessionId: empty file")
                return null
            }

            // Find first user message from start
            var parseCount = 0
            val firstUserMessage = lines
                .firstNotNullOfOrNull { line ->
                    parseCount++
                    parseMessageFromLine(line)?.takeIf { it.role == ChatMessage.Role.USER }
                }?.content?.firstOrNull()?.content
            val firstMsgTime = System.currentTimeMillis()

            // Find last timestamp from end  
            var reverseParseCount = 0
            val lastTimestamp = lines
                .reversed()
                .firstNotNullOfOrNull { line ->
                    reverseParseCount++
                    parseMessageFromLine(line)?.timestamp
                }
            val lastMsgTime = System.currentTimeMillis()

            if (lastTimestamp == null) {
                println("[SessionMetadataExtractor] $sessionId: no valid timestamps found")
                return null
            }

            val preview = firstUserMessage ?: "Empty session"
            val totalTime = System.currentTimeMillis() - startTime

            println(
                "[SessionMetadataExtractor] $sessionId: ${lines.size} lines, " +
                        "parsed ${parseCount}+${reverseParseCount} lines, " +
                        "read:${readTime - startTime}ms first:${firstMsgTime - readTime}ms last:${lastMsgTime - firstMsgTime}ms total:${totalTime}ms"
            )

            return ChatSession(
                sessionId = sessionId,
                projectPath = sessionFile.parentFile.decodeProjectPath(),
                firstMessage = firstUserMessage ?: "",
                lastTimestamp = lastTimestamp,
                messageCount = lines.size,
                preview = preview
            )

        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            println("[SessionMetadataExtractor] ERROR ${sessionFile.name}: ${e.message} (${totalTime}ms)")
            return null
        }
    }

    private fun parseMessageFromLine(jsonLine: String): ChatMessage? {
        return try {
            val entry = parser.parseJsonLine(jsonLine) ?: return null

            (entry as? ClaudeCodeSessionEntryV1_0.Message)?.let { message ->
                mapper.run { message.toChatMessage() }
            }
        } catch (_: Exception) {
            null
        }
    }
}