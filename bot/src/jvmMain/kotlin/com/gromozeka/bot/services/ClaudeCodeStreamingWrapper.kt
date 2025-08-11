package com.gromozeka.bot.services

import com.gromozeka.bot.model.StreamJsonLine
import com.gromozeka.bot.model.StreamJsonLinePacket
import com.gromozeka.bot.settings.AppMode
import com.gromozeka.bot.settings.ResponseFormat
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

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

    private fun loadSystemPrompt(responseFormat: ResponseFormat): String {
        // Load format-specific prompt
        val basePrompt = SystemPromptLoader.loadPrompt(responseFormat)
        println("[ClaudeCodeStreamingWrapper] Loaded prompt for format: $responseFormat")

        var prompt = if (settingsService.mode == AppMode.DEV) {
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

        // Add current time to prompt if enabled
        if (settingsService.settings.includeCurrentTime) {
            val now = LocalDateTime.now()
            val zoneId = ZoneId.systemDefault()
            val zoneOffset = zoneId.rules.getOffset(now)

            val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            val dayOfWeek = now.dayOfWeek.toString().lowercase()
                .replaceFirstChar { it.uppercase() }

            val envBlock = """
                
                <env>
                Today's date: ${now.format(dateFormatter)}
                Current time: ${now.format(timeFormatter)} ${zoneId.id} (UTC${zoneOffset})
                Day of week: $dayOfWeek
                </env>
            """.trimIndent()

            prompt = "$prompt\n$envBlock"
            println("[ClaudeCodeStreamingWrapper] Added current time to prompt: ${now.format(dateFormatter)} ${now.format(timeFormatter)} ${zoneId.id}")
        }

        return prompt
    }

    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var stdoutReader: BufferedReader? = null
    private var stderrReader: BufferedReader? = null
    private var readJob: Job? = null
    private var stderrJob: Job? = null
    private var streamLogger: StreamLogger? = null


    // Unified stream broadcasting with buffer for late subscribers
    private val _streamMessages = MutableSharedFlow<StreamJsonLinePacket>(
        replay = 0,  // No replay to prevent cross-session message contamination
        extraBufferCapacity = 100  // Buffer capacity for fast emission
    )
    val streamMessages: SharedFlow<StreamJsonLinePacket> = _streamMessages.asSharedFlow()

    suspend fun start(
        projectPath: String? = null,
        model: String? = null,
        responseFormat: ResponseFormat = ResponseFormat.JSON,
        resumeSessionId: String? = null,
    ) = withContext(Dispatchers.IO) {
        try {
            println("=== STARTING CLAUDE CODE STREAMING WRAPPER ===")


            val systemPrompt = loadSystemPrompt(responseFormat).replace("\"", "\\\"")
            
            // NOTE: In stream-json mode, Claude Code sends multiple init messages - this is NORMAL behavior.
            // We confirmed this by testing claude-code-sdk-python: each user message triggers a new init.
            // The session ID remains the same across all init messages.
            // See docs/claude-code-streaming-behavior.md for detailed analysis.
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
                systemPrompt
            )

            // Add model parameter if specified
            if (!model.isNullOrBlank()) {
                command.add("--model")
                command.add(model)
                println("[ClaudeCodeStreamingWrapper] Using model: $model")
            }

            // Add resume parameter if specified
            if (!resumeSessionId.isNullOrBlank()) {
                command.add("--resume")
                command.add(resumeSessionId)
                println("[ClaudeCodeStreamingWrapper] Resuming session: $resumeSessionId")
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

            // According to Claude Code SDK docs: don't send session_id at all
            // When null, kotlinx.serialization won't include the field in JSON
            val streamJsonMessage = UserInputMessage(
                type = "user",
                message = UserInputMessage.Content(
                    role = "user",
                    content = listOf(
                        UserInputMessage.Content.TextBlock(
                            text = message
                        )
                    )
                ),
                session_id = sessionId,  // Never send session_id - let Claude manage it
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

    suspend fun sendControlMessage(controlMessage: StreamJsonLine.ControlRequest) = withContext(Dispatchers.IO) {
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

    fun streamOutput(): Flow<StreamJsonLinePacket> = streamMessages

    private fun parseStreamJsonLine(jsonLine: String): StreamJsonLinePacket {

        val originalJson = if (settingsService.settings.showOriginalJson) {
            jsonLine
        } else {
            null
        }

        return try {
            val parsed = Json.decodeFromString<StreamJsonLine>(jsonLine)
            println("[ClaudeCodeStreamingWrapper] Successfully parsed StreamJsonLine: ${parsed.type}")

            // Special logging for control messages
            when (parsed) {
                is StreamJsonLine.ControlResponse -> {
                    println("[ClaudeWrapper] Control response received: request_id=${parsed.response.requestId}, subtype=${parsed.response.subtype}, error=${parsed.response.error}")
                }

                is StreamJsonLine.ControlRequest -> {
                    println("[ClaudeWrapper] Control request parsed: request_id=${parsed.requestId}, subtype=${parsed.request.subtype}")
                }

                else -> {}
            }

            StreamJsonLinePacket(parsed, originalJson)
        } catch (e: SerializationException) {
            println("[ClaudeCodeStreamingWrapper] STREAM PARSE ERROR: Failed to deserialize StreamJsonLine")
            println("  Exception: ${e.javaClass.simpleName}: ${e.message}")
            println("  JSON line: $jsonLine")
            println("  Stack trace: ${e.stackTraceToString()}")
            val fallbackMessage = try {
                StreamJsonLine.System(
                    subtype = "parse_error",
                    data = Json.parseToJsonElement(jsonLine) as JsonObject,
                    sessionId = null
                )
            } catch (e: Exception) {
                println("[ClaudeCodeStreamingWrapper] SECONDARY ERROR: Failed to parse as JsonObject")
                println("  Exception: ${e.javaClass.simpleName}: ${e.message}")
                StreamJsonLine.System(
                    subtype = "raw_output",
                    data = buildJsonObject { put("raw_line", JsonPrimitive(jsonLine)) },
                    sessionId = null
                )
            }

            StreamJsonLinePacket(fallbackMessage, originalJson)
        } catch (e: Exception) {
            println("[ClaudeCodeStreamingWrapper] UNEXPECTED ERROR in parseStreamJsonLine: ${e.javaClass.simpleName}: ${e.message}")
            println("  JSON line: $jsonLine")
            println("  Stack trace: ${e.stackTraceToString()}")
            val fallbackMessage = StreamJsonLine.System(
                subtype = "error",
                data = buildJsonObject { put("error", JsonPrimitive(e.message ?: "Unknown error")) },
                sessionId = null
            )

            StreamJsonLinePacket(fallbackMessage, originalJson)
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
                    val streamMessagePacket = parseStreamJsonLine(line)
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
        val session_id: String? = null,  // Made nullable to test without session_id
        val parent_tool_use_id: String? = null,
    ) {
        @Serializable
        data class Content(
            val role: String,
            val content: List<ContentBlock>,
        ) {
            // Note: We can't have explicit 'type' field due to kotlinx.serialization limitation
            // The 'type' field is added automatically as discriminator by @SerialName
            // See: https://github.com/Kotlin/kotlinx.serialization/issues/1664
            @Serializable
            sealed class ContentBlock

            @Serializable
            @SerialName("text")
            data class TextBlock(
                // val type: String = "text",  // Added automatically by @SerialName
                val text: String
            ) : ContentBlock()

            @Serializable
            @SerialName("image")
            data class ImageBlock(
                // val type: String = "image",  // Added automatically by @SerialName
                val source: ImageSource
            ) : ContentBlock() {
                @Serializable
                data class ImageSource(
                    val type: String = "base64",
                    val media_type: String,  // "image/jpeg", "image/png", "image/gif", "image/webp"
                    val data: String  // base64 encoded image
                )
            }

            // Tool results are handled by Claude Code CLI internally
            // We only send user messages with text and images
        }
    }

    @PreDestroy
    fun cleanup() {
        println("CLEARING STREAM")
        scope.cancel()
    }
}

