package com.gromozeka.bot.services

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
 * Project path encoding: /Users/lewik/code/project → -Users-lewik-code-project
 */
class StreamLogger(
    private val projectPath: String,
    private val sessionId: String? = null,
) {
    companion object {
        private const val BASE_DIR = ".gromozeka/streamlogs"
        private const val LOG_RETENTION_DAYS = 30L

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
                                println("[StreamLogger] Deleted old log: ${file.fileName}")
                            }
                        } catch (e: Exception) {
                            println("[StreamLogger] Error checking/deleting file $file: ${e.message}")
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
                                println("[StreamLogger] Deleted empty directory: ${dir.fileName}")
                            }
                        } catch (e: Exception) {
                            // Directory not empty or other error, skip
                        }
                    }
            } catch (e: Exception) {
                println("[StreamLogger] Error during cleanup: ${e.message}")
            }
        }
    }

    private val logDir: Path
    private var currentWriter: BufferedWriter? = null
    private var currentSessionId: String? = sessionId
    private val writerLock = Any()

    init {
        val encodedPath = encodeProjectPath(projectPath)
        logDir = Paths.get(System.getProperty("user.home"), BASE_DIR, encodedPath)

        // Create directories if needed
        logDir.createDirectories()

        // Initialize writer if session ID is already known
        sessionId?.let { initWriter(it) }

        println("[StreamLogger] Initialized for project: $projectPath")
        println("[StreamLogger] Log directory: $logDir")
    }

    /**
     * Update session ID and reinitialize writer
     */
    fun updateSessionId(newSessionId: String) {
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
                    println("[StreamLogger] Error writing log: ${e.message}")
                }
            } ?: run {
                // Fallback: create writer with unix timestamp if no session ID yet
                val fallbackId = System.currentTimeMillis().toString()
                println("[StreamLogger] No session ID yet, creating fallback log: $fallbackId.jsonl")
                initWriter(fallbackId)

                // Try to write the line immediately
                currentWriter?.let { writer ->
                    try {
                        writer.write(line)
                        writer.newLine()
                        writer.flush()
                    } catch (e: Exception) {
                        println("[StreamLogger] Error writing to fallback log: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Initialize writer for a specific session
     */
    private fun initWriter(sessionId: String) {
        try {
            val logFile = logDir.resolve("$sessionId.jsonl").toFile()
            currentWriter = BufferedWriter(FileWriter(logFile, true)) // Append mode
            println("[StreamLogger] Started logging to: ${logFile.absolutePath}")
        } catch (e: Exception) {
            println("[StreamLogger] Error creating log writer: ${e.message}")
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
                println("[StreamLogger] Error closing writer: ${e.message}")
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