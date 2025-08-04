package com.gromozeka.bot.services

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.flow.*
import com.gromozeka.bot.model.StreamMessage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

class ClaudeCodeStreamingWrapper {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        explicitNulls = true
        encodeDefaults = true
    }

    private fun loadDefaultSystemPrompt(): String {
        return this::class.java.getResourceAsStream("/default-system-prompt.md")
            ?.bufferedReader()
            ?.readText()
            ?: ""
    }

    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var stdoutReader: BufferedReader? = null
    private var readJob: Job? = null


    private var currentSessionId: String? = null
    
    // Unified stream broadcasting with buffer for late subscribers
    private val _streamMessages = MutableSharedFlow<StreamMessage>(
        replay = 100,  // Keep last 100 messages for late subscribers
        extraBufferCapacity = 100  // Buffer capacity for fast emission
    )
    val streamMessages: SharedFlow<StreamMessage> = _streamMessages.asSharedFlow()

    suspend fun start(
        projectPath: String? = null,
        onSessionIdCaptured: (String) -> Unit = { },
    ) = withContext(Dispatchers.IO) {
        try {
            println("=== STARTING CLAUDE CODE STREAMING WRAPPER ===")

            currentSessionId = null

            val defaultPrompt = loadDefaultSystemPrompt().replace("\"", "\\\"")
            // Возвращаем как было - с дублированием, но рабочее
            val command = listOf(
                "claude",
                "--output-format", "stream-json",
                "--input-format", "stream-json", 
                "--verbose",
                "--append-system-prompt", defaultPrompt
            )
            
            // Truncate system prompt for cleaner logs
            val truncatedCommand = command.mapIndexed { index, arg ->
                if (index > 0 && command.getOrNull(index - 1) == "--append-system-prompt" && arg.length > 100) {
                    "<SYSTEM_PROMPT_TRUNCATED_${arg.length}_CHARS>"
                } else {
                    arg
                }
            }
            println("[ClaudeCodeStreamingWrapper] EXECUTING COMMAND: ${truncatedCommand.joinToString(" ")}")
            println("[ClaudeCodeStreamingWrapper] FULL COMMAND: $truncatedCommand")

            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(false)
            
            // Устанавливаем переменную окружения как в Python SDK для headless режима
            val env = processBuilder.environment()
            env["CLAUDE_CODE_ENTRYPOINT"] = "sdk-py"

            if (projectPath != null) {
                processBuilder.directory(File(projectPath))
            }

            process = processBuilder.start()

            val proc = process ?: throw IllegalStateException("Failed to start process")

            stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))
            stdoutReader = BufferedReader(proc.inputStream.reader())

            readJob = scope.launch {
                readOutputStream(onSessionIdCaptured)
            }


        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun sendMessage(message: String, sessionId: String? = null) = withContext(Dispatchers.IO) {
        try {
            val proc = process
            println("[ClaudeCodeStreamingWrapper] Process alive before sending: ${proc?.isAlive()}")
            
            val actualSessionId = sessionId ?: currentSessionId ?: "default"
            val streamJsonMessage = UserInputMessage(
                type = "user",
                message = UserInputMessage.Content(
                    role = "user",
                    content = message
                ),
                session_id = actualSessionId,
                parent_tool_use_id = null
            )

            val jsonLine = json.encodeToString(streamJsonMessage)
            println("[ClaudeCodeStreamingWrapper] Writing to stdin: $jsonLine")
            println("[ClaudeCodeStreamingWrapper] Session ID used: $actualSessionId")

            val writer = stdinWriter ?: throw IllegalStateException("Process not started")
            writer.write("$jsonLine\n")
            writer.flush()
            
            // Проверяем через небольшую задержку
            delay(1000)
            println("[ClaudeCodeStreamingWrapper] Process alive after sending: ${proc?.isAlive()}")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun streamOutput(): Flow<StreamMessage> = streamMessages

    private fun parseStreamMessage(jsonLine: String): StreamMessage {
        return try {
            Json.decodeFromString<StreamMessage>(jsonLine)
        } catch (e: SerializationException) {
            try {
                StreamMessage.SystemStreamMessage(
                    subtype = "parse_error",
                    data = Json.parseToJsonElement(jsonLine) as JsonObject,
                    sessionId = null
                )
            } catch (e: Exception) {
                StreamMessage.SystemStreamMessage(
                    subtype = "raw_output", 
                    data = buildJsonObject { put("raw_line", JsonPrimitive(jsonLine)) },
                    sessionId = null
                )
            }
        }
    }

    private suspend fun readOutputStream(onSessionIdCaptured: (String) -> Unit) {
        try {
            val reader = stdoutReader ?: return

            while (true) {
                val line = reader.readLine() ?: break
                if (line.trim().isEmpty()) continue

                try {
                    println("=== CLAUDE CODE LIVE STREAM ===")
                    println(line)
                    println("=== END LIVE STREAM ===")

                    // Parse and broadcast stream message
                    val streamMessage = parseStreamMessage(line)
                    println("[ClaudeWrapper] *** EMITTING STREAM MESSAGE: ${streamMessage.type}")
                    
                    _streamMessages.emit(streamMessage)
                    
                    // Extract session ID for callback
                    extractSessionId(line)?.let { sessionId ->
                        println("[ClaudeCodeStreamingWrapper] Captured session ID: $sessionId")
                        onSessionIdCaptured(sessionId)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
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


        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractSessionId(jsonLine: String) = try {
        val jsonObject = Json.parseToJsonElement(jsonLine) as? JsonObject
        val type = jsonObject?.get("type")?.jsonPrimitive?.content
        val subtype = jsonObject?.get("subtype")?.jsonPrimitive?.content
        if (type == "system" && subtype == "init") {
            val sessionId = jsonObject["session_id"]?.jsonPrimitive?.content
            if (sessionId != null) {
                currentSessionId = sessionId
                println("[ClaudeCodeStreamingWrapper] Updated currentSessionId to: $sessionId")
            }
            sessionId
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    @Serializable
    data class UserInputMessage(
        val type: String,
        val message: Content,
        val session_id: String = "default",
        val parent_tool_use_id: String? = null
    ) {
        @Serializable
        data class Content(
            val role: String,
            val content: String,
        )
    }

    @PreDestroy
    fun cleanup() {
        scope.cancel()
    }
}

