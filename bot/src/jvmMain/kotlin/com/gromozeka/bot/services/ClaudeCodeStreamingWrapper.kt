package com.gromozeka.bot.services

import com.gromozeka.bot.model.StreamMessage
import com.gromozeka.bot.model.StreamMessagePacket
import com.gromozeka.bot.settings.AppMode
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

@Component
class ClaudeCodeStreamingWrapper(
    private val settingsService: SettingsService,
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        explicitNulls = true
        encodeDefaults = true
    }

    private fun loadDefaultSystemPrompt(): String {
        val basePrompt = this::class.java.getResourceAsStream("/default-system-prompt.md")
            ?.bufferedReader()
            ?.readText()
            ?: ""

        return if (settingsService.mode == AppMode.DEV) {
            println("[ClaudeCodeStreamingWrapper] DEV mode detected - loading additional DEV prompt")
            val devPrompt = this::class.java.getResourceAsStream("/dev-mode-prompt.md")
                ?.bufferedReader()
                ?.readText()
                ?: ""

            if (devPrompt.isNotEmpty()) {
                println("[ClaudeCodeStreamingWrapper] DEV prompt loaded successfully (${devPrompt.length} chars)")
                "$basePrompt\n\n$devPrompt"
            } else {
                println("[ClaudeCodeStreamingWrapper] WARNING: DEV prompt file empty or not found")
                basePrompt
            }
        } else {
            println("[ClaudeCodeStreamingWrapper] PROD mode - using base prompt only")
            basePrompt
        }
    }

    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var stdoutReader: BufferedReader? = null
    private var stderrReader: BufferedReader? = null
    private var readJob: Job? = null
    private var stderrJob: Job? = null
    private var streamLogger: StreamLogger? = null


    // Unified stream broadcasting with buffer for late subscribers
    private val _streamMessages = MutableSharedFlow<StreamMessagePacket>(
        replay = 0,  // No replay to prevent cross-session message contamination
        extraBufferCapacity = 100  // Buffer capacity for fast emission
    )
    val streamMessages: SharedFlow<StreamMessagePacket> = _streamMessages.asSharedFlow()

    suspend fun start(
        projectPath: String? = null,
        model: String? = null,
    ) = withContext(Dispatchers.IO) {
        try {
            println("=== STARTING CLAUDE CODE STREAMING WRAPPER ===")


            val defaultPrompt = loadDefaultSystemPrompt().replace("\"", "\\\"")
            val command = mutableListOf(
                "claude",
                "--output-format",
                "stream-json",
                "--input-format",
                "stream-json",
                "--verbose",
                "--permission-mode",
                "acceptEdits",
                "--append-system-prompt",
                defaultPrompt
            )

            // Add model parameter if specified
            if (!model.isNullOrBlank()) {
                command.add("--model")
                command.add(model)
                println("[ClaudeCodeStreamingWrapper] Using model: $model")
            }

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

            // Set environment variable like in Python SDK for headless mode
            val env = processBuilder.environment()
//            env["CLAUDE_CODE_ENTRYPOINT"] = "sdk-py"

            val actualProjectPath = projectPath ?: System.getProperty("user.dir")
            if (projectPath != null) {
                processBuilder.directory(File(projectPath))
            }

            // Initialize stream logger for this project
            streamLogger = StreamLogger(actualProjectPath)

            process = processBuilder.start()

            val proc = process ?: throw IllegalStateException("Failed to start process")
            println("[ClaudeCodeStreamingWrapper] Process started with PID: ${proc.pid()}")

            stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))
            stdoutReader = BufferedReader(proc.inputStream.reader())
            stderrReader = BufferedReader(proc.errorStream.reader())

            readJob = scope.launchReadOutputStream()
            stderrJob = scope.launchReadErrorStream()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun sendMessage(message: String, sessionId: String) = withContext(Dispatchers.IO) {
        try {
            val proc = process
            println("[ClaudeCodeStreamingWrapper] Process alive before sending: ${proc?.isAlive()}")

            val streamJsonMessage = UserInputMessage(
                type = "user",
                message = UserInputMessage.Content(
                    role = "user",
                    content = message
                ),
                session_id = sessionId,
                parent_tool_use_id = null
            )

            val jsonLine = json.encodeToString(streamJsonMessage)
            println("[ClaudeCodeStreamingWrapper] Writing to stdin: $jsonLine")
            println("[ClaudeCodeStreamingWrapper] Session ID used: $sessionId")

            val writer = stdinWriter ?: throw IllegalStateException("Process not started")
            writer.write("$jsonLine\n")
            writer.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun sendControlMessage(controlMessage: StreamMessage.ControlRequest) = withContext(Dispatchers.IO) {
        try {
            val proc = process
            if (proc == null || !proc.isAlive) {
                throw IllegalStateException("Claude process is not running")
            }

            val writer = stdinWriter
            if (writer == null) {
                throw IllegalStateException("stdin writer not available")
            }

            // Create control request structure as per Claude Code CLI protocol
            val controlRequestJson = buildJsonObject {
                put("type", "control_request")
                put("request_id", controlMessage.requestId)
                putJsonObject("request") {
                    put("subtype", controlMessage.request.subtype)
                }
            }

            val jsonLine = controlRequestJson.toString()
            println("[ClaudeWrapper] Sending control message: $jsonLine")

            writer.write("$jsonLine\n")
            writer.flush()
        } catch (e: Exception) {
            println("[ClaudeWrapper] Control message failed: ${e.message}")
            throw e
        }
    }

    fun streamOutput(): Flow<StreamMessagePacket> = streamMessages

    private fun parseStreamMessage(jsonLine: String): StreamMessagePacket {

        val originalJson = if (settingsService.settings.showOriginalJson) {
            jsonLine
        } else {
            null
        }

        return try {
            val parsed = Json.decodeFromString<StreamMessage>(jsonLine)
            println("[ClaudeCodeStreamingWrapper] Successfully parsed StreamMessage: ${parsed.type}")

            // Special logging for control messages
            when (parsed) {
                is StreamMessage.ControlResponse -> {
                    println("[ClaudeWrapper] Control response received: request_id=${parsed.response.requestId}, subtype=${parsed.response.subtype}, error=${parsed.response.error}")
                }

                is StreamMessage.ControlRequest -> {
                    println("[ClaudeWrapper] Control request parsed: request_id=${parsed.requestId}, subtype=${parsed.request.subtype}")
                }

                else -> {}
            }

            StreamMessagePacket(parsed, originalJson)
        } catch (e: SerializationException) {
            println("[ClaudeCodeStreamingWrapper] STREAM PARSE ERROR: Failed to deserialize StreamMessage")
            println("  Exception: ${e.javaClass.simpleName}: ${e.message}")
            println("  JSON line: $jsonLine")
            println("  Stack trace: ${e.stackTraceToString()}")
            val fallbackMessage = try {
                StreamMessage.System(
                    subtype = "parse_error",
                    data = Json.parseToJsonElement(jsonLine) as JsonObject,
                    sessionId = null
                )
            } catch (e: Exception) {
                println("[ClaudeCodeStreamingWrapper] SECONDARY ERROR: Failed to parse as JsonObject")
                println("  Exception: ${e.javaClass.simpleName}: ${e.message}")
                StreamMessage.System(
                    subtype = "raw_output",
                    data = buildJsonObject { put("raw_line", JsonPrimitive(jsonLine)) },
                    sessionId = null
                )
            }
            
            StreamMessagePacket(fallbackMessage, originalJson)
        } catch (e: Exception) {
            println("[ClaudeCodeStreamingWrapper] UNEXPECTED ERROR in parseStreamMessage: ${e.javaClass.simpleName}: ${e.message}")
            println("  JSON line: $jsonLine")
            println("  Stack trace: ${e.stackTraceToString()}")
            val fallbackMessage = StreamMessage.System(
                subtype = "error",
                data = buildJsonObject { put("error", JsonPrimitive(e.message ?: "Unknown error")) },
                sessionId = null
            )
            
            StreamMessagePacket(fallbackMessage, originalJson)
        }
    }

    private suspend fun CoroutineScope.launchReadOutputStream() = launch {
        try {
            val reader = stdoutReader
            require(reader != null) { "Reader was null" }

            while (true) {
                val line = reader.readLine() ?: break
                if (line.trim().isEmpty()) continue

                try {
                    println("=== CLAUDE CODE LIVE STREAM ===")
                    println(line)
                    println("=== END LIVE STREAM ===")

                    // Parse and broadcast stream message
                    val streamMessagePacket = parseStreamMessage(line)
                    println("[ClaudeWrapper] *** EMITTING STREAM MESSAGE: ${streamMessagePacket.streamMessage.type}")

                    _streamMessages.emit(streamMessagePacket)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // Log raw JSONL line after all processing (guaranteed)
                    streamLogger?.logLine(line)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun CoroutineScope.launchReadErrorStream() = launch {
        try {
            val reader = stderrReader
            require(reader != null) { "Stderr reader was null" }

            while (true) {
                val line = reader.readLine() ?: break
                if (line.trim().isEmpty()) continue

                println("[ClaudeCodeStreamingWrapper] STDERR: $line")
            }

        } catch (e: Exception) {
            println("[ClaudeCodeStreamingWrapper] Error reading stderr: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            readJob?.cancel()
            stderrJob?.cancel()
            stdinWriter?.close()
            stdoutReader?.close()
            stderrReader?.close()
            streamLogger?.close()  // Close logger before destroying process
            process?.destroy()

            // Wait a bit for graceful shutdown
            delay(1000)

            if (process?.isAlive == true) {
                process?.destroyForcibly()
            }


        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            streamLogger = null
        }
    }

    private fun extractSessionId(jsonLine: String) = try {
        val jsonObject = Json.parseToJsonElement(jsonLine) as? JsonObject
        val type = jsonObject?.get("type")?.jsonPrimitive?.content
        val subtype = jsonObject?.get("subtype")?.jsonPrimitive?.content
        if (type == "system" && subtype == "init") {
            val sessionId = jsonObject["session_id"]?.jsonPrimitive?.content
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
        val session_id: String,
        val parent_tool_use_id: String? = null,
    ) {
        @Serializable
        data class Content(
            val role: String,
            val content: String,
        )
    }

    @PreDestroy
    fun cleanup() {
        println("CLEARING STREAM")
        scope.cancel()
    }
}

