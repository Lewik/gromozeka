package com.gromozeka.bot.services

import com.gromozeka.bot.model.ChatSessionMetadata
import com.gromozeka.bot.model.ClaudeLogEntry
import com.gromozeka.bot.model.StreamSessionMetadata
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import com.gromozeka.shared.domain.session.toSessionUuid
import com.gromozeka.shared.domain.session.toClaudeSessionUuid
import com.gromozeka.bot.utils.SessionDeduplicator
import com.gromozeka.bot.utils.decodeProjectPath
import com.gromozeka.bot.utils.encodeProjectPath
import com.gromozeka.bot.utils.isSessionFile
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.io.File

/**
 * Service for loading messages and metadata from Claude Code session JSONL files.
 * Extracted from SessionJsonl to provide stateless historical data loading.
 */
@Service
class SessionJsonlService(
    private val settingsService: SettingsService,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = false
    }

    /**
     * Load messages from a specific session file.
     * @param sessionId The session ID (filename without .jsonl extension)
     * @param projectPath The project path where the session was created
     * @return List of ChatMessage from the session, empty if file doesn't exist or errors occur
     */
    suspend fun loadMessagesFromSession(
        sessionId: ClaudeSessionUuid,
        projectPath: String,
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        val sessionFile = findSessionFile(sessionId.value, projectPath)
        if (sessionFile == null || !sessionFile.exists()) {
            println("[SessionJsonlService] Session file not found for ID: $sessionId")
            return@withContext emptyList()
        }

        try {
            val lines = sessionFile.readLines()
            if (lines.isEmpty()) {
                println("[SessionJsonlService] Session file is empty: $sessionId")
                return@withContext emptyList()
            }

            // Parse all Claude log entries first
            val claudeEntries = lines.mapIndexedNotNull { index, line ->
                try {
                    json.decodeFromString<ClaudeLogEntry>(line.trim())
                } catch (e: SerializationException) {
                    println("[SessionJsonlService] Error parsing line ${index + 1} in session $sessionId: ${e.message}")
                    println("  Problematic line: ${line.take(200)}${if (line.length > 200) "..." else ""}")
                    null
                } catch (e: Exception) {
                    println("[SessionJsonlService] Unexpected error parsing line ${index + 1} in session $sessionId: ${e.message}")
                    println("  Exception type: ${e::class.simpleName}")
                    println("  Problematic line: ${line.take(200)}${if (line.length > 200) "..." else ""}")
                    null
                }
            }

            // Apply deduplication to fix Claude Code CLI bug with stream-json
            val deduplicatedEntries = SessionDeduplicator.deduplicate(claudeEntries)

            // Log deduplication stats if duplicates were found
            if (deduplicatedEntries.size < claudeEntries.size) {
                val stats = SessionDeduplicator.getDeduplicationStats(claudeEntries, deduplicatedEntries)
                println("[SessionJsonlService] Deduplicated ${stats.duplicatesRemoved} duplicate entries (${stats.userDuplicates} user, ${stats.assistantDuplicates} assistant)")
            }

            // Convert deduplicated entries to chat messages
            val messages = deduplicatedEntries.mapNotNull { claudeEntry ->
                try {
                    ClaudeLogEntryMapper.mapToChatMessage(claudeEntry)
                } catch (e: Exception) {
                    println("[SessionJsonlService] Error mapping Claude entry to chat message: ${e.message}")
                    null
                }
            }

            println("[SessionJsonlService] Successfully loaded ${messages.size} messages from session $sessionId")
            return@withContext messages

        } catch (e: Exception) {
            println("[SessionJsonlService] Error loading messages for session $sessionId: ${e.message}")
            println("  Session file: ${sessionFile.absolutePath}")
            println("  File exists: ${sessionFile.exists()}")
            println("  File size: ${sessionFile.length()} bytes")
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    /**
     * Load metadata from a specific session file.
     * @param sessionId The session ID (filename without .jsonl extension)
     * @param projectPath The project path where the session was created
     * @return StreamSessionMetadata or null if file doesn't exist or errors occur
     */
    suspend fun loadMetadataFromSession(
        sessionId: ClaudeSessionUuid,
        projectPath: String,
    ): StreamSessionMetadata? = withContext(Dispatchers.IO) {
        val sessionFile = findSessionFile(sessionId.value, projectPath)
        if (sessionFile == null || !sessionFile.exists()) {
            println("[SessionJsonlService] Session file not found for metadata: $sessionId")
            return@withContext null
        }

        try {
            val lines = sessionFile.readLines()
            val fileLastModified = kotlinx.datetime.Instant.fromEpochMilliseconds(sessionFile.lastModified())
                .toLocalDateTime(TimeZone.currentSystemDefault())

            if (lines.isEmpty()) {
                return@withContext StreamSessionMetadata(
                    sessionId = sessionId.value.toSessionUuid(),
                    projectPath = projectPath,
                    title = "Empty Session",
                    lastModified = fileLastModified,
                    messageCount = 0
                )
            }

            // Parse first few messages to generate title
            val messages = lines.take(3).mapIndexedNotNull { index, line ->
                try {
                    val claudeEntry = json.decodeFromString<ClaudeLogEntry>(line.trim())
                    ClaudeLogEntryMapper.mapToChatMessage(claudeEntry)
                } catch (e: Exception) {
                    println("[SessionJsonlService] Error parsing line ${index + 1} for metadata in session $sessionId: ${e.message}")
                    null
                }
            }

            val title = generateSessionTitle(messages)

            return@withContext StreamSessionMetadata(
                sessionId = sessionId.value.toSessionUuid(),
                projectPath = projectPath,
                title = title,
                lastModified = fileLastModified,
                messageCount = lines.size
            )

        } catch (e: Exception) {
            println("[SessionJsonlService] Error loading metadata for session $sessionId: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Find session file by ID and project path.
     */
    private fun findSessionFile(sessionId: String, projectPath: String): File? {
        val encodedProjectPath = projectPath.encodeProjectPath()
        val projectDir = File(settingsService.getClaudeProjectsDir(), encodedProjectPath)
        val sessionFile = projectDir.resolve("$sessionId.jsonl")

        return if (sessionFile.exists()) {
            sessionFile
        } else {
            println("[SessionJsonlService] Session file not found at: ${sessionFile.absolutePath}")
            null
        }
    }

    /**
     * Generate session title from first messages.
     */
    private fun generateSessionTitle(messages: List<ChatMessage>): String {
        val firstUserMessage = messages.firstOrNull { it.role == ChatMessage.Role.USER }

        return when {
            firstUserMessage != null -> {
                val contentText = firstUserMessage.content
                    .filterIsInstance<ChatMessage.ContentItem.UserMessage>()
                    .joinToString(" ") { it.text }
                val content = contentText.take(50)
                if (contentText.length > 50) "$content..." else content
            }

            messages.isNotEmpty() -> {
                val contentText = messages.first().content
                    .filterIsInstance<ChatMessage.ContentItem.UserMessage>()
                    .joinToString(" ") { it.text }
                val content = contentText.take(50)
                if (contentText.length > 50) "$content..." else content
            }

            else -> "Empty Session"
        }
    }

    /**
     * Load all sessions from Claude Code's projects directory
     * @return List of ChatSession objects representing all available sessions
     */
    suspend fun loadAllSessionsMetadata(): List<ChatSessionMetadata> = withContext(Dispatchers.IO) {
        val projectsDir = settingsService.getClaudeProjectsDir()
        if (!projectsDir.exists()) {
            println("[SessionJsonlService] Projects directory doesn't exist: ${projectsDir.absolutePath}")
            return@withContext emptyList()
        }

        // Find all session files across all projects
        val sessionFiles = projectsDir.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" && it.isSessionFile() }
            .toList()

        println("[SessionJsonlService] Found ${sessionFiles.size} session files")

        // Load sessions in parallel
        val sessions = sessionFiles.map { file ->
            async {
                try {
                    val sessionId = file.nameWithoutExtension.toClaudeSessionUuid()
                    val projectPath = file.parentFile?.decodeProjectPath()
                        ?: throw IllegalArgumentException("Cannot determine project path for file: ${file.path}")

                    // Load metadata to get title and message count
                    val metadata = loadMetadataFromSession(sessionId, projectPath)

                    if (metadata != null) {
                        ChatSessionMetadata(
                            claudeSessionId = sessionId,
                            projectPath = projectPath,
                            firstMessage = metadata.title,
                            lastTimestamp = metadata.lastModified.toInstant(TimeZone.currentSystemDefault()),
                            messageCount = metadata.messageCount,
                            preview = metadata.title
                        )
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    println("[SessionJsonlService] Error loading session from file ${file.path}: ${e.message}")
                    null
                }
            }
        }.awaitAll().filterNotNull()

        println("[SessionJsonlService] Successfully loaded ${sessions.size} sessions")
        return@withContext sessions.sortedByDescending { it.lastTimestamp }
    }
}