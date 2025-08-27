package com.gromozeka.bot.services

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import com.gromozeka.shared.domain.session.toClaudeSessionUuid
import klog.KLoggers

import java.io.BufferedWriter
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import kotlin.io.path.*

/**
 * Logs raw JSONL stream data from Claude Code CLI for testing and debugging.
 *
 * Directory structure: ~/.gromozeka/streamlogs/{encoded-project-path}/{sessionId}.jsonl
 * Project path encoding: /Users/slavik/code/project → -Users-slavik-code-project
 */
class StreamLogger(
    private val projectPath: String,
    private val sessionId: ClaudeSessionUuid? = null,
) {
    companion object {
        private const val BASE_DIR = ".gromozeka/streamlogs"
        private const val LOG_RETENTION_DAYS = 30L
        private val log = KLoggers.logger(StreamLogger::class.java)

        /**
         * Encode project path to directory-safe format (like Claude does)
         */
        fun encodeProjectPath(path: String): String {
            return path.replace("/", "-")
                .replace("\\", "-")
                .replace(":", "-")
                .trim('-')
        }

        /**
         * Clean up logs older than retention period
         */
        fun cleanupOldLogs() {
            val baseDir = Paths.get(System.getProperty("user.home"), BASE_DIR)
            if (!baseDir.exists()) return

            val cutoffTime = Instant.now().minus(Duration.ofDays(LOG_RETENTION_DAYS))

            try {
                Files.walk(baseDir)
                    .filter { it.isRegularFile() && it.extension == "jsonl" }
                    .forEach { file ->
                        try {
                            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
                            val lastModified = attrs.lastModifiedTime().toInstant()

                            if (lastModified.isBefore(cutoffTime)) {
                                Files.delete(file)
                                log.debug("Deleted old log: ${file.fileName}")
                            }
                        } catch (e: Exception) {
                            log.error(e) { "Error checking/deleting file $file: ${e.message}" }
                        }
                    }

                // Clean up empty directories
                Files.walk(baseDir)
                    .sorted(Comparator.reverseOrder()) // Process deepest first
                    .filter { it.isDirectory() && it != baseDir }
                    .forEach { dir ->
                        try {
                            if (dir.listDirectoryEntries().isEmpty()) {
                                Files.delete(dir)
                                log.debug("Deleted empty directory: ${dir.fileName}")
                            }
                        } catch (e: Exception) {
                            // Directory not empty or other error, skip
                        }
                    }
            } catch (e: Exception) {
                log.error(e) { "Error during cleanup: ${e.message}" }
            }
        }
    }

    private val logDir: Path
    private var currentWriter: BufferedWriter? = null
    private var currentSessionId: ClaudeSessionUuid? = sessionId
    private val writerLock = Any()

    init {
        val encodedPath = encodeProjectPath(projectPath)
        logDir = Paths.get(System.getProperty("user.home"), BASE_DIR, encodedPath)

        // Create directories if needed
        logDir.createDirectories()

        // Initialize writer if session ID is already known
        sessionId?.let { initWriter(it) }

        log.debug("Initialized for project: $projectPath")
        log.debug("Log directory: $logDir")
    }

    /**
     * Update session ID and reinitialize writer
     */
    fun updateSessionId(newSessionId: ClaudeSessionUuid) {
        synchronized(writerLock) {
            if (currentSessionId != newSessionId) {
                closeWriter()
                currentSessionId = newSessionId
                initWriter(newSessionId)
            }
        }
    }

    /**
     * Log a raw JSONL line from Claude stream
     */
    fun logLine(line: String) {
        if (line.isBlank()) return

        synchronized(writerLock) {
            currentWriter?.let { writer ->
                try {
                    writer.write(line)
                    writer.newLine()
                    writer.flush() // Immediate flush for crash safety
                } catch (e: Exception) {
                    log.error(e) { "Error writing log: ${e.message}" }
                }
            } ?: run {
                // Fallback: create writer with unix timestamp if no session ID yet
                val fallbackId = System.currentTimeMillis().toString()
                log.warn("No session ID yet, creating fallback log: $fallbackId.jsonl")
                initWriter(fallbackId.toClaudeSessionUuid())

                // Try to write the line immediately
                currentWriter?.let { writer ->
                    try {
                        writer.write(line)
                        writer.newLine()
                        writer.flush()
                    } catch (e: Exception) {
                        log.error(e) { "Error writing to fallback log: ${e.message}" }
                    }
                }
            }
        }
    }

    /**
     * Initialize writer for a specific session
     */
    private fun initWriter(sessionId: ClaudeSessionUuid) {
        try {
            val logFile = logDir.resolve("${sessionId.value}.jsonl").toFile()
            currentWriter = BufferedWriter(FileWriter(logFile, true)) // Append mode
            log.debug("Started logging to: ${logFile.absolutePath}")
        } catch (e: Exception) {
            log.error(e) { "Error creating log writer: ${e.message}" }
        }
    }

    /**
     * Close current writer
     */
    private fun closeWriter() {
        currentWriter?.let { writer ->
            try {
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                log.error(e) { "Error closing writer: ${e.message}" }
            }
            currentWriter = null
        }
    }

    /**
     * Clean up resources
     */
    fun close() {
        synchronized(writerLock) {
            closeWriter()
        }
    }
}