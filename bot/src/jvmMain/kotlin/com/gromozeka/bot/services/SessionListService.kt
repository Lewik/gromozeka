package com.gromozeka.bot.services

import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.model.ProjectGroup
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files

@Service
class SessionListService {
    
    private val objectMapper = ObjectMapper()
    
    /**
     * Get list of available Claude Code sessions from all projects
     */
    fun getAvailableSessions(): List<ChatSession> {
        val claudeProjectsDir = File(System.getProperty("user.home"), ".claude/projects")
        
        if (!claudeProjectsDir.exists()) {
            return emptyList()
        }
        
        return claudeProjectsDir.listFiles { file -> 
            file.isDirectory
        }?.flatMap { projectDir ->
            val encodedPath = projectDir.name
            val decodedPath = encodedPath.decodeProjectPath()
            
            println("Scanning project: $decodedPath (encoded: $encodedPath)")
            
            projectDir.listFiles { file ->
                file.extension == "jsonl" && !file.name.endsWith(".backup")
            }?.mapNotNull { sessionFile ->
                parseSessionFile(sessionFile, decodedPath)
            } ?: emptyList()
        }?.sortedByDescending { it.lastTimestamp } ?: emptyList()
    }
    
    /**
     * Parse single session file to extract metadata
     */
    private fun parseSessionFile(file: File, projectPath: String): ChatSession? {
        return try {
            val lines = Files.readAllLines(file.toPath())
            if (lines.isEmpty()) {
                println("Skipping empty session file: ${file.name}")
                return null
            }
            
            val firstLine = objectMapper.readTree(lines.first())
            val lastLine = objectMapper.readTree(lines.last())
            
            val sessionId = file.name.extractSessionId()
            val firstMessage = extractMessageContent(firstLine)
            
            // Check if timestamp exists, skip session if not
            val timestampNode = lastLine.get("timestamp")
            if (timestampNode == null) {
                println("Skipping session ${sessionId}: no timestamp found")
                return null
            }
            
            val lastTimestamp = Instant.parse(timestampNode.asText())
            val messageCount = lines.size
            val preview = if (firstMessage.isBlank()) "Empty session" else firstMessage
            
            println("Parsed session ${sessionId}: preview='${preview.take(30)}...', messages=$messageCount, project=${projectPath.substringAfterLast('/')}")
            
            ChatSession(
                sessionId = sessionId,
                projectPath = projectPath,
                firstMessage = firstMessage,
                lastTimestamp = lastTimestamp,
                messageCount = messageCount,
                preview = preview
            )
        } catch (e: Exception) {
            println("Error parsing session file ${file.name}: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Extract session ID from filename (remove .jsonl extension)
     */
    private fun String.extractSessionId() = this.removeSuffix(".jsonl")
    
    /**
     * Extract message content from JSON node
     */
    private fun extractMessageContent(jsonNode: JsonNode): String {
        val message = jsonNode.get("message")
        return when {
            message?.has("content") == true -> {
                val content = message.get("content")
                when {
                    content.isArray && content.size() > 0 -> {
                        content.get(0).get("text")?.asText() ?: ""
                    }
                    content.isTextual -> content.asText()
                    else -> ""
                }
            }
            else -> ""
        }
    }
    
    /**
     * Encode project path for Claude Code storage format
     */
    private fun String.encodeProjectPath() = replace("/", "-")
    
    /**
     * Decode project path from Claude Code storage format
     */
    private fun String.decodeProjectPath() = replace("-", "/")
    
    /**
     * Get sessions grouped by project with metadata
     */
    fun getSessionsGroupedByProject(): List<ProjectGroup> {
        return getAvailableSessions()
            .groupBy { it.projectPath }
            .map { (projectPath, sessions) ->
                ProjectGroup(
                    projectPath = projectPath,
                    projectName = extractProjectName(projectPath),
                    sessions = sessions.sortedByDescending { it.lastTimestamp }
                )
            }
            .sortedByDescending { group -> 
                group.lastActivity() 
            }
    }
    
    /**
     * Extract project name from path (last segment)
     */
    private fun extractProjectName(projectPath: String): String {
        return when {
            projectPath.contains("/") -> projectPath.substringAfterLast("/")
            projectPath.isNotBlank() -> projectPath
            else -> "Unknown Project"
        }
    }
}