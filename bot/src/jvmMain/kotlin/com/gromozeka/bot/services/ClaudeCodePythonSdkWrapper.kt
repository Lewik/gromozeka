package com.gromozeka.bot.services

import com.gromozeka.bot.model.StreamJsonLine
import com.gromozeka.bot.model.StreamJsonLinePacket
import com.gromozeka.bot.settings.AppMode
import com.gromozeka.bot.settings.ResponseFormat
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Alternative wrapper that uses Claude Code Python SDK instead of direct CLI.
 * This wrapper launches claude_proxy_server.py process which uses the official
 * claude-code-sdk-python to communicate with Claude.
 *
 * Benefits over direct CLI:
 * - Better message handling through official SDK
 * - Cleaner async/await patterns
 * - More robust error handling
 * - SDK-managed session state
 *
 * The proxy server translates between SDK messages and our stream-json format
 * to maintain compatibility with the existing Gromozeka architecture.
 */
class ClaudeCodePythonSdkWrapper(
    private val settingsService: SettingsService,
) : ClaudeWrapper {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        explicitNulls = true
        encodeDefaults = true
    }

    private fun loadSystemPrompt(responseFormat: ResponseFormat): String {
        val basePrompt = SystemPromptLoader.loadPrompt(responseFormat)
        println("[ClaudeCodePythonSdkWrapper] Loaded prompt for format: $responseFormat")

        var prompt = if (settingsService.mode == AppMode.DEV) {
            println("[ClaudeCodePythonSdkWrapper] DEV mode detected - loading additional DEV prompt")
            val devPrompt = this::class.java.getResourceAsStream("/dev-mode-prompt.md")
                ?.bufferedReader()
                ?.readText()
                ?: ""

            if (devPrompt.isNotEmpty()) {
                println("[ClaudeCodePythonSdkWrapper] DEV prompt loaded successfully (${devPrompt.length} chars)")
                "$basePrompt\n\n$devPrompt"
            } else {
                println("[ClaudeCodePythonSdkWrapper] WARNING: DEV prompt file empty or not found")
                basePrompt
            }
        } else {
            println("[ClaudeCodePythonSdkWrapper] PROD mode - using base prompt only")
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
            println(
                "[ClaudeCodePythonSdkWrapper] Added current time to prompt: ${now.format(dateFormatter)} ${
                    now.format(
                        timeFormatter
                    )
                } ${zoneId.id}"
            )
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

    override suspend fun start(
        projectPath: String?,
        model: String?,
        responseFormat: ResponseFormat,
        resumeSessionId: ClaudeSessionUuid?,
        customSystemPrompt: String?,
    ) = withContext(Dispatchers.IO) {
        try {
            println("=== STARTING CLAUDE CODE PYTHON SDK WRAPPER ===")

            val systemPrompt = customSystemPrompt ?: loadSystemPrompt(responseFormat)

            // Find Python interpreter
            val pythonCommand = findPythonCommand()

            // Find the proxy server script
            val proxyScriptPath = findProxyServerScript()

            // Build command to launch the proxy server
            val command = mutableListOf(
                pythonCommand,
                proxyScriptPath
            )

            // Pass configuration via environment variables
            val env = mutableMapOf<String, String>()
            env["CLAUDE_SYSTEM_PROMPT"] = systemPrompt
            env["CLAUDE_PERMISSION_MODE"] = "acceptEdits"

            if (!model.isNullOrBlank()) {
                env["CLAUDE_MODEL"] = model
                println("[ClaudeCodePythonSdkWrapper] Using model: $model")
            }

            if (resumeSessionId != null) {
                env["CLAUDE_RESUME_SESSION"] = resumeSessionId.value
                println("[ClaudeCodePythonSdkWrapper] Resuming session: $resumeSessionId")
            }

            println("[ClaudeCodePythonSdkWrapper] EXECUTING COMMAND: ${command.joinToString(" ")}")

            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(false)

            // Set environment variables
            val processEnv = processBuilder.environment()
            processEnv.putAll(env)

            val actualProjectPath = projectPath ?: System.getProperty("user.dir")
            if (projectPath != null) {
                processBuilder.directory(File(projectPath))
            }

            // Initialize stream logger for this project
            streamLogger = StreamLogger(actualProjectPath)

            process = processBuilder.start()

            val proc = process ?: throw IllegalStateException("Failed to start process")
            println("[ClaudeCodePythonSdkWrapper] Process started with PID: ${proc.pid()}")

            stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))
            stdoutReader = BufferedReader(proc.inputStream.reader())
            stderrReader = BufferedReader(proc.errorStream.reader())

            readJob = scope.launchReadOutputStream()
            stderrJob = scope.launchReadErrorStream()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun findPythonCommand(): String {
        // First, check for virtual environment in project
        val venvPaths = listOf(
            // In Gromozeka dev directory
            File(System.getProperty("user.home"), "code/gromozeka/dev/python-sdk-venv/bin/python"),
            File(System.getProperty("user.home"), "code/gromozeka/release/python-sdk-venv/bin/python"),
            // Relative to current directory
            File(System.getProperty("user.dir"), "python-sdk-venv/bin/python"),
            // Common venv names
            File(System.getProperty("user.dir"), "venv/bin/python"),
            File(System.getProperty("user.dir"), ".venv/bin/python")
        )

        // Check virtual environments first
        for (venvPath in venvPaths) {
            if (venvPath.exists() && venvPath.canExecute()) {
                println("[ClaudeCodePythonSdkWrapper] Found Python in venv: ${venvPath.absolutePath}")
                return venvPath.absolutePath
            }
        }

        // Fall back to system Python
        val pythonCommands = listOf("python3", "python", "python3.13", "python3.12", "python3.11")

        for (cmd in pythonCommands) {
            try {
                val process = ProcessBuilder(cmd, "--version").start()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    println("[ClaudeCodePythonSdkWrapper] Found system Python: $cmd")
                    return cmd
                }
            } catch (e: Exception) {
                // Try next command
            }
        }

        throw IllegalStateException("Python interpreter not found. Please install Python 3.8 or later.")
    }

    private fun findProxyServerScript(): String {
        // Look for claude_proxy_server.py in several locations
        val possibleLocations = listOf(
            // In the current directory
            File(System.getProperty("user.dir"), "claude_proxy_server.py"),
            // In the Gromozeka dev directory
            File(System.getProperty("user.home"), "code/gromozeka/dev/claude_proxy_server.py"),
            // In the Gromozeka release directory
            File(System.getProperty("user.home"), "code/gromozeka/release/claude_proxy_server.py"),
            // Relative to JAR location
            File(
                this::class.java.protectionDomain.codeSource?.location?.toURI()
                    ?.resolve("../claude_proxy_server.py")?.path ?: ""
            )
        )

        for (location in possibleLocations) {
            if (location.exists() && location.isFile) {
                println("[ClaudeCodePythonSdkWrapper] Found proxy server script at: ${location.absolutePath}")
                return location.absolutePath
            }
        }

        // If not found, use the most likely location with a warning
        val defaultPath = File(System.getProperty("user.home"), "code/gromozeka/dev/claude_proxy_server.py").absolutePath
        println("[ClaudeCodePythonSdkWrapper] WARNING: Proxy server script not found, using default: $defaultPath")
        return defaultPath
    }

    override suspend fun sendMessage(message: String, sessionId: ClaudeSessionUuid) = withContext(Dispatchers.IO) {
        try {
            val proc = process
            println("[ClaudeCodePythonSdkWrapper] Process alive before sending: ${proc?.isAlive}")

            // Create user message in the same format as ClaudeCodeStreamingWrapper
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
                session_id = sessionId.value,
                parent_tool_use_id = null
            )

            val jsonLine = json.encodeToString(streamJsonMessage)
            println("[ClaudeCodePythonSdkWrapper] Writing to stdin: $jsonLine")
            println("[ClaudeCodePythonSdkWrapper] Session ID used: $sessionId")

            val writer = stdinWriter ?: throw IllegalStateException("Process not started")
            writer.write("$jsonLine\n")
            writer.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun sendControlMessage(controlMessage: StreamJsonLine.ControlRequest) =
        withContext(Dispatchers.IO) {
            try {
                val proc = process
                if (proc == null || !proc.isAlive) {
                    throw IllegalStateException("Claude proxy process is not running")
                }

                val writer = stdinWriter
                if (writer == null) {
                    throw IllegalStateException("stdin writer not available")
                }

                // Create control request structure
                val controlRequestJson = buildJsonObject {
                    put("type", "control_request")
                    put("request_id", controlMessage.requestId)
                    putJsonObject("request") {
                        put("subtype", controlMessage.request.subtype)
                    }
                }

                val jsonLine = controlRequestJson.toString()
                println("[ClaudeCodePythonSdkWrapper] Sending control message: $jsonLine")

                writer.write("$jsonLine\n")
                writer.flush()
            } catch (e: Exception) {
                println("[ClaudeCodePythonSdkWrapper] Control message failed: ${e.message}")
                throw e
            }
        }

    override fun streamOutput(): Flow<StreamJsonLinePacket> = streamMessages

    private fun parseStreamJsonLine(jsonLine: String): StreamJsonLinePacket {

        val originalJson = if (settingsService.settings.showOriginalJson) {
            jsonLine
        } else {
            null
        }

        return try {
            val parsed = Json.decodeFromString<StreamJsonLine>(jsonLine)
            println("[ClaudeCodePythonSdkWrapper] Successfully parsed StreamJsonLine: ${parsed.type}")

            // Special logging for control messages
            when (parsed) {
                is StreamJsonLine.ControlResponse -> {
                    println("[ClaudeCodePythonSdkWrapper] Control response received: request_id=${parsed.response.requestId}, subtype=${parsed.response.subtype}, error=${parsed.response.error}")
                }

                is StreamJsonLine.ControlRequest -> {
                    println("[ClaudeCodePythonSdkWrapper] Control request parsed: request_id=${parsed.requestId}, subtype=${parsed.request.subtype}")
                }

                else -> {}
            }

            StreamJsonLinePacket(parsed, originalJson)
        } catch (e: SerializationException) {
            println("[ClaudeCodePythonSdkWrapper] STREAM PARSE ERROR: Failed to deserialize StreamJsonLine")
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
                println("[ClaudeCodePythonSdkWrapper] SECONDARY ERROR: Failed to parse as JsonObject")
                println("  Exception: ${e.javaClass.simpleName}: ${e.message}")
                StreamJsonLine.System(
                    subtype = "raw_output",
                    data = buildJsonObject { put("raw_line", JsonPrimitive(jsonLine)) },
                    sessionId = null
                )
            }

            StreamJsonLinePacket(fallbackMessage, originalJson)
        } catch (e: Exception) {
            println("[ClaudeCodePythonSdkWrapper] UNEXPECTED ERROR in parseStreamJsonLine: ${e.javaClass.simpleName}: ${e.message}")
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
                    println("=== CLAUDE PYTHON SDK STREAM ===")
                    println(line)
                    println("=== END SDK STREAM ===")

                    // Parse and broadcast stream message
                    val streamMessagePacket = parseStreamJsonLine(line)
                    println("[ClaudeCodePythonSdkWrapper] *** EMITTING STREAM MESSAGE: ${streamMessagePacket.streamMessage.type}")

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

                println("[ClaudeCodePythonSdkWrapper] STDERR: $line")
            }

        } catch (e: Exception) {
            println("[ClaudeCodePythonSdkWrapper] Error reading stderr: ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            // Cancel all coroutines in scope (includes readJob and stderrJob)
            scope.cancel()

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

    // Reuse the same UserInputMessage data class from ClaudeCodeStreamingWrapper
    @Serializable
    data class UserInputMessage(
        val type: String,
        val message: Content,
        val session_id: String? = null,
        val parent_tool_use_id: String? = null,
    ) {
        @Serializable
        data class Content(
            val role: String,
            val content: List<ContentBlock>,
        ) {
            @Serializable
            sealed class ContentBlock

            @Serializable
            @SerialName("text")
            data class TextBlock(
                val text: String,
            ) : ContentBlock()

            @Serializable
            @SerialName("image")
            data class ImageBlock(
                val source: ImageSource,
            ) : ContentBlock() {
                @Serializable
                data class ImageSource(
                    val type: String = "base64",
                    val media_type: String,
                    val data: String,
                )
            }
        }
    }
}