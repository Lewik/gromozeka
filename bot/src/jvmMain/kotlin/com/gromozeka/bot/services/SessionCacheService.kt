package com.gromozeka.bot.services

import com.gromozeka.bot.model.*
import com.gromozeka.bot.utils.ClaudeCodePaths
import com.gromozeka.bot.utils.decodeProjectPath
import com.gromozeka.bot.utils.isSessionFile
import com.gromozeka.bot.utils.sha256
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.stereotype.Service
import java.io.File

@Service
class SessionCacheService {

    private val _sessionsFlow = MutableStateFlow<List<ProjectGroup>>(emptyList())
    val sessionsFlow: StateFlow<List<ProjectGroup>> = _sessionsFlow.asStateFlow()

    private val fileCache = mutableMapOf<String, List<SessionLineRecord>>()
    private val sessionMetadataCache = mutableMapOf<String, ChatSession>()

    private val claudeCodeSessionMapper = ClaudeCodeSessionMapper

    suspend fun refreshAll() {
        fileCache.clear()
        sessionMetadataCache.clear()

        ClaudeCodePaths.PROJECTS_DIR
            .listFiles { it.isDirectory }
            .forEach { projectDir ->
                projectDir
                    .listFiles { it.isSessionFile() }
                    .forEach { loadSessionFile(it) }
            }

        updateSessionsFlow()
    }

    suspend fun updateFile(sessionFile: File) {
        val sessionId = sessionFile.name.removeSuffix(".jsonl")
        val oldMessages = getSessionMessages(sessionId)
        
        loadSessionFile(sessionFile)
        updateSessionsFlow()
        
        val newMessages = getSessionMessages(sessionId)
        println("[SessionCacheService] updateFile for session $sessionId: old=${oldMessages.size}, new=${newMessages.size}")

    }

    suspend fun removeSession(file: File) {
        fileCache.remove(file.name)
        val sessionId = file.name.removeSuffix(".jsonl")
        sessionMetadataCache.remove(sessionId)
        updateSessionsFlow()
    }

    fun compareFile(file: File, newFileContent: List<String>): FileComparison {
        val oldRecords = fileCache[file.name] ?: emptyList()
        val oldHashes = oldRecords.associate { it.lineNumber to it.contentHash }

        val newRecords = newFileContent.mapIndexed { index, line ->
            SessionLineRecord(
                fileName = file.name,
                lineNumber = index,
                contentHash = line.sha256(),
                jsonContent = line,
                parsedMessage = safeParseMessage(line)
            )
        }

        val newHashes = newRecords.associate { it.lineNumber to it.contentHash }

        return FileComparison(
            newLines = newRecords.filter { it.lineNumber >= oldRecords.size },
            modifiedLines = newRecords.filter { record ->
                oldHashes[record.lineNumber] != record.contentHash
            },
            deletedLineNumbers = oldRecords.filter { record ->
                !newHashes.containsKey(record.lineNumber)
            }.map { it.lineNumber }
        )
    }

    fun getSessionMessages(sessionId: String): List<ChatMessage> {
        val fileName = "$sessionId.jsonl"
        return fileCache[fileName]?.mapNotNull { it.parsedMessage } ?: emptyList()
    }

    private fun loadSessionFile(sessionFile: File) {
        try {

            val lineRecords = sessionFile
                .readLines()
                .mapIndexed { index, line ->
                    SessionLineRecord(
                        fileName = sessionFile.name,
                        lineNumber = index,
                        contentHash = line.sha256(),
                        jsonContent = line,
                        parsedMessage = safeParseMessage(line)
                    )
                }

            fileCache[sessionFile.name] = lineRecords

            createSessionMetadata(sessionFile, lineRecords)

        } catch (e: Exception) {
            println("Error loading session file ${sessionFile.name}: ${e.message}")
        }
    }

    private fun createSessionMetadata(
        sessionFile: File,
        lineRecords: List<SessionLineRecord>,
    ) {
        val sessionId = sessionFile.name.removeSuffix(".jsonl")
        val messages = lineRecords.mapNotNull { it.parsedMessage }

        if (messages.isEmpty()) return

        val firstMessage =
            messages.firstOrNull { it.role == ChatMessage.Role.USER }?.content?.firstOrNull()?.content ?: ""
        val lastTimestamp = messages.maxOfOrNull { it.timestamp } ?: return

        val session = ChatSession(
            sessionId = sessionId,
            projectPath = sessionFile.parentFile.decodeProjectPath(),
            firstMessage = firstMessage,
            lastTimestamp = lastTimestamp,
            messageCount = messages.size,
            preview = firstMessage.ifBlank { "Empty session" }
        )

        sessionMetadataCache[sessionId] = session
    }

    private fun updateSessionsFlow() {
        val projectGroups = sessionMetadataCache.values
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

        _sessionsFlow.value = projectGroups
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

        } catch (e: Exception) {
            println("Failed to parse JSON line: ${jsonLine.take(50)}... Error: ${e.message}")
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
