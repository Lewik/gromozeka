package com.gromozeka.bot.services

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

@Service
class ClaudeCodeStreamingWrapper {

    private val objectMapper = ObjectMapper().apply {
        registerKotlinModule()
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private fun loadDefaultSystemPrompt(): String {
        return this::class.java.getResourceAsStream("/default-system-prompt.md")
            ?.bufferedReader()
            ?.readText()
            ?: ""
    }

    private fun parseStructuredResponse(textContent: String): StructuredResponse? {
        return try {

            if (textContent.startsWith("{")) {
                val jsonData = objectMapper.readValue(textContent, Map::class.java) as Map<String, Any>

                val result = StructuredResponse(
                    fullText = jsonData["full_text"] as? String 
                        ?: jsonData["response"] as? String 
                        ?: textContent,
                    ttsText = jsonData["tts_text"] as? String 
                        ?: jsonData["speak"] as? String 
                        ?: jsonData["voiceResponse"] as? String
                        ?: "",
                    voiceTone = jsonData["voice_tone"] as? String 
                        ?: jsonData["tone"] as? String 
                        ?: jsonData["emotion"] as? String
                        ?: "neutral colleague"
                )
                result
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var stdoutReader: BufferedReader? = null
    private var readJob: Job? = null
    
    private val _messages = MutableSharedFlow<ClaudeStreamMessage>()
    val messages: SharedFlow<ClaudeStreamMessage> = _messages.asSharedFlow()
    
    private val _status = MutableSharedFlow<StreamStatus>()
    val status: SharedFlow<StreamStatus> = _status.asSharedFlow()

    // Current session ID
    private var currentSessionId: String? = null

    suspend fun start(sessionId: String? = null, projectPath: String? = null) = withContext(Dispatchers.IO) {
        try {
            println("=== STARTING CLAUDE CODE STREAMING WRAPPER ===")
            
            currentSessionId = sessionId
            
            // Start Claude Code in interactive mode with specified session through bash
            val defaultPrompt = loadDefaultSystemPrompt().replace("\"", "\\\"")
            val claudeArgs = if (sessionId != null) {
                "--output-format stream-json --input-format stream-json --verbose --resume $sessionId --append-system-prompt \"$defaultPrompt\""
            } else {  
                "--output-format stream-json --input-format stream-json --verbose --append-system-prompt \"$defaultPrompt\""
            }
            val command = listOf(
                "bash", "-c", "claude $claudeArgs"
            )
            
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(false) // Keep stderr separate for debugging
            
            // Set working directory if projectPath is provided
            if (projectPath != null) {
                processBuilder.directory(File(projectPath))
            }
            
            process = processBuilder.start()
            
            val proc = process ?: throw IllegalStateException("Failed to start process")
            
            // Setup streams
            stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))
            stdoutReader = BufferedReader(proc.inputStream.reader())
            
            // Start reading output in background
            readJob = CoroutineScope(Dispatchers.IO).launch {
                readOutputStream()
            }
            
            _status.emit(StreamStatus.CONNECTED)
            
        } catch (e: Exception) {
            e.printStackTrace()
            _status.emit(StreamStatus.ERROR(e.message ?: "Unknown error"))
        }
    }

    suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        try {
            val writer = stdinWriter ?: throw IllegalStateException("Process not started")
            
            // Create stream-json formatted input
            val streamJsonMessage = mapOf(
                "type" to "user",
                "message" to mapOf(
                    "role" to "user",
                    "content" to message
                )
            )
            
            val jsonLine = objectMapper.writeValueAsString(streamJsonMessage)
            
            writer.write("$jsonLine\n")
            writer.flush()
            
            _status.emit(StreamStatus.MESSAGE_SENT)
            
        } catch (e: Exception) {
            e.printStackTrace()
            _status.emit(StreamStatus.ERROR(e.message ?: "Failed to send message"))
        }
    }

    private suspend fun readOutputStream() {
        try {
            val reader = stdoutReader ?: return
            
            while (true) {
                val line = reader.readLine() ?: break
                if (line.trim().isEmpty()) continue
                
                try {
                    // Parse JSON line
                    val messageData = objectMapper.readValue(line, Map::class.java) as Map<String, Any>
                    val message = parseStreamMessage(messageData)
                    _messages.emit(message)
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            _status.emit(StreamStatus.DISCONNECTED)
            
        } catch (e: Exception) {
            e.printStackTrace()
            _status.emit(StreamStatus.ERROR(e.message ?: "Stream reading error"))
        }
    }

    private fun parseStreamMessage(data: Map<String, Any>): ClaudeStreamMessage {
        val type = data["type"] as? String ?: "unknown"
        
        return when (type) {
            "system" -> ClaudeStreamMessage.SystemMessage(
                subtype = data["subtype"] as? String,
                sessionId = data["session_id"] as? String
            )
            "assistant" -> {
                val messageData = data["message"] as? Map<String, Any>
                val content = messageData?.get("content") as? List<Map<String, Any>>
                val textContent = content?.firstOrNull { it["type"] == "text" }?.get("text") as? String ?: ""
                
                val structuredResponse = parseStructuredResponse(textContent)
                
                ClaudeStreamMessage.AssistantMessage(
                    text = textContent,
                    sessionId = data["session_id"] as? String,
                    structuredResponse = structuredResponse
                )
            }
            "result" -> ClaudeStreamMessage.ResultMessage(
                isError = data["is_error"] as? Boolean ?: false,
                sessionId = data["session_id"] as? String,
                totalCostUsd = data["total_cost_usd"] as? Double,
                durationMs = data["duration_ms"] as? Int,
                numTurns = data["num_turns"] as? Int
            )
            else -> ClaudeStreamMessage.UnknownMessage(type, data)
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            readJob?.cancel()
            stdinWriter?.close()
            stdoutReader?.close()
            process?.destroy()
            
            // Wait a bit for graceful shutdown
            delay(1000)
            
            if (process?.isAlive == true) {
                process?.destroyForcibly()
            }
            
            _status.emit(StreamStatus.DISCONNECTED)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

data class StructuredResponse(
    val fullText: String,
    val ttsText: String,
    val voiceTone: String
)

sealed class ClaudeStreamMessage {
    data class SystemMessage(
        val subtype: String?,
        val sessionId: String?
    ) : ClaudeStreamMessage()
    
    data class AssistantMessage(
        val text: String,
        val sessionId: String?,
        val structuredResponse: StructuredResponse? = null
    ) : ClaudeStreamMessage()
    
    data class ResultMessage(
        val isError: Boolean,
        val sessionId: String?,
        val totalCostUsd: Double?,
        val durationMs: Int?,
        val numTurns: Int?
    ) : ClaudeStreamMessage()
    
    data class UnknownMessage(
        val type: String,
        val data: Map<String, Any>
    ) : ClaudeStreamMessage()
}

sealed class StreamStatus {
    object CONNECTED : StreamStatus()
    object DISCONNECTED : StreamStatus()
    object MESSAGE_SENT : StreamStatus()
    data class ERROR(val message: String) : StreamStatus()
}