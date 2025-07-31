package com.gromozeka.bot.services

import com.gromozeka.bot.model.*
import com.gromozeka.bot.utils.ClaudeCodePaths
import com.gromozeka.bot.utils.isSessionFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service
import java.io.File

@Service
class SessionCacheService {


    private val sessionMetadataCache = mutableMapOf<String, ChatSession>()
    private val activeSessionCache = mutableMapOf<String, List<ChatMessage>>()
    private var currentActiveSessionId: String? = null

    private val metadataExtractor = SessionMetadataExtractor()
    private val claudeCodeSessionMapper = ClaudeCodeSessionMapper

    suspend fun loadSessionsList(): List<ProjectGroup> {
        sessionMetadataCache.clear()

        // Process everything in parallel
        val metadataResults = coroutineScope {
            ClaudeCodePaths.PROJECTS_DIR
                .listFiles { it.isDirectory }
                ?.flatMap { projectDir ->
                    listOf(async(Dispatchers.IO) {
                        projectDir.listFiles { it.isSessionFile() }
                            ?.map { sessionFile ->
                                metadataExtractor.extractMetadata(sessionFile)
                            }?.filterNotNull() ?: emptyList()
                    })
                }?.map { it.await() }?.flatten() ?: emptyList()
        }

        // Collect results  
        metadataResults.forEach { metadata ->
            sessionMetadataCache[metadata.sessionId] = metadata
        }

        // Return project groups directly instead of using flow
        return sessionMetadataCache.values
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

    suspend fun updateFile(sessionFile: File) {
        val sessionId = sessionFile.name.removeSuffix(".jsonl")

        // Update metadata
        metadataExtractor.extractMetadata(sessionFile)?.let { metadata ->
            sessionMetadataCache[metadata.sessionId] = metadata
        }

        // Clear active cache if this session is currently active
        if (currentActiveSessionId == sessionId) {
            activeSessionCache.remove(sessionId)
        }

    }

    suspend fun removeSession(file: File) {
        val sessionId = file.name.removeSuffix(".jsonl")
        sessionMetadataCache.remove(sessionId)
        activeSessionCache.remove(sessionId)
        if (currentActiveSessionId == sessionId) {
            currentActiveSessionId = null
        }
    }


    fun getSessionMessages(sessionId: String): List<ChatMessage> {
        // Check if session is already in active cache
        activeSessionCache[sessionId]?.let { return it }

        // Load session on demand
        val sessionFile = findSessionFile(sessionId) ?: return emptyList()

        val messages = loadFullSessionMessages(sessionFile)

        // Clear previous active session to save memory
        currentActiveSessionId?.let { activeSessionCache.remove(it) }

        // Cache new active session
        activeSessionCache[sessionId] = messages
        currentActiveSessionId = sessionId

        return messages
    }

    private fun findSessionFile(sessionId: String): File? {
        return ClaudeCodePaths.PROJECTS_DIR
            .listFiles { it.isDirectory }
            ?.flatMap { projectDir ->
                projectDir.listFiles { it.isSessionFile() }?.toList() ?: emptyList()
            }
            ?.find { it.name == "$sessionId.jsonl" }
    }

    private fun loadFullSessionMessages(sessionFile: File): List<ChatMessage> {
        return try {
            sessionFile.readLines()
                .mapNotNull { line -> safeParseMessage(line) }
        } catch (_: Exception) {
            emptyList()
        }
    }



    private fun safeParseMessage(jsonLine: String): ChatMessage? {
        return try {
            val parser = ClaudeCodeSessionParser()
            val entries = parser.parseJsonLine(jsonLine)?.let { listOf(it) } ?: emptyList()

            entries.mapNotNull { entry ->
                (entry as? ClaudeCodeSessionEntryV1_0.Message)?.let { message ->
                    claudeCodeSessionMapper.run { message.toChatMessage() }
                }
            }.firstOrNull()

        } catch (_: Exception) {
            null
        }
    }

    private fun extractProjectName(projectPath: String): String {
        return when {
            projectPath.contains("/") -> projectPath.substringAfterLast("/")
            projectPath.isNotBlank() -> projectPath
            else -> "Unknown Project"
        }
    }

}
