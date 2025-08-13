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
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
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
 * Wrapper that uses Claude Code Node.js SDK instead of direct CLI.
 * This wrapper launches claude_proxy_server.js process which uses the official
 * @anthropic-ai/claude-code SDK to communicate with Claude.
 * 
 * Benefits over direct CLI:
 * - Native SDK (Claude Code itself is Node.js)
 * - Better async/await patterns with native Promises
 * - More robust error handling
 * - Built-in abort controller support
 * - Direct access to SDK features like interrupt()
 * 
 * The proxy server translates between SDK messages and our stream-json format
 * to maintain compatibility with the existing Gromozeka architecture.
 */
class ClaudeCodeNodeSdkWrapper(
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
        println("[ClaudeCodeNodeSdkWrapper] Loaded prompt for format: $responseFormat")

        var prompt = if (settingsService.mode == AppMode.DEV) {
            println("[ClaudeCodeNodeSdkWrapper] DEV mode detected - loading additional DEV prompt")
            val devPrompt = this::class.java.getResourceAsStream("/dev-mode-prompt.md")
                ?.bufferedReader()
                ?.readText()
                ?: ""

            if (devPrompt.isNotEmpty()) {
                println("[ClaudeCodeNodeSdkWrapper] DEV prompt loaded successfully (${devPrompt.length} chars)")
                "$basePrompt\n\n$devPrompt"
            } else {
                println("[ClaudeCodeNodeSdkWrapper] WARNING: DEV prompt file empty or not found")
                basePrompt
            }
        } else {
            println("[ClaudeCodeNodeSdkWrapper] PROD mode - using base prompt only")
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
            println("[ClaudeCodeNodeSdkWrapper] Added current time to prompt: ${now.format(dateFormatter)} ${now.format(timeFormatter)} ${zoneId.id}")
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
    ) = withContext(Dispatchers.IO) {
        try {
            println("=== STARTING CLAUDE CODE NODE SDK WRAPPER ===")

            val systemPrompt = loadSystemPrompt(responseFormat)
            
            // Find Node.js interpreter
            val nodeCommand = findNodeCommand()
            
            // Find the proxy server script
            val proxyScriptPath = findProxyServerScript()
            
            // Build command to launch the proxy server
            val command = mutableListOf(
                nodeCommand,
                proxyScriptPath
            )

            // Pass configuration via environment variables
            val env = mutableMapOf<String, String>()
            env["CLAUDE_SYSTEM_PROMPT"] = systemPrompt
            env["CLAUDE_PERMISSION_MODE"] = "acceptEdits"
            
            if (!model.isNullOrBlank()) {
                env["CLAUDE_MODEL"] = model
                println("[ClaudeCodeNodeSdkWrapper] Using model: $model")
            }

            if (resumeSessionId != null) {
                env["CLAUDE_RESUME_SESSION"] = resumeSessionId.value
                println("[ClaudeCodeNodeSdkWrapper] Resuming session: $resumeSessionId")
            }

            println("[ClaudeCodeNodeSdkWrapper] EXECUTING COMMAND: ${command.joinToString(" ")}")
            
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
            println("[ClaudeCodeNodeSdkWrapper] Process started with PID: ${proc.pid()}")

            stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))
            stdoutReader = BufferedReader(proc.inputStream.reader())
            stderrReader = BufferedReader(proc.errorStream.reader())

            readJob = scope.launchReadOutputStream()
            stderrJob = scope.launchReadErrorStream()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun findNodeCommand(): String {
        // Check for Node.js in common locations
        val nodeCommands = listOf("node", "nodejs", "/usr/local/bin/node", "/opt/homebrew/bin/node")
        
        for (cmd in nodeCommands) {
            try {
                val process = ProcessBuilder(cmd, "--version").start()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    val version = process.inputStream.bufferedReader().readText().trim()
                    println("[ClaudeCodeNodeSdkWrapper] Found Node.js: $cmd (version: $version)")
                    
                    // Check if version is 18+ (required by Claude Code SDK)
                    val majorVersion = version.removePrefix("v").split(".").firstOrNull()?.toIntOrNull() ?: 0
                    if (majorVersion < 18) {
                        println("[ClaudeCodeNodeSdkWrapper] WARNING: Node.js version $version is below required v18")
                    }
                    
                    return cmd
                }
            } catch (e: Exception) {
                // Try next command
            }
        }
        
        throw IllegalStateException("Node.js not found. Please install Node.js 18 or later.")
    }
    
    private fun findProxyServerScript(): String {
        // Look for claude_proxy_server.mjs (ES module) in several locations
        val possibleLocations = listOf(
            // In the current directory
            File(System.getProperty("user.dir"), "claude_proxy_server.mjs"),
            // In the Gromozeka dev directory
            File("/Users/lewik/code/gromozeka/dev/claude_proxy_server.mjs"),
            // In the Gromozeka release directory
            File("/Users/lewik/code/gromozeka/release/claude_proxy_server.mjs"),
            // Relative to JAR location
            File(this::class.java.protectionDomain.codeSource?.location?.toURI()?.resolve("../claude_proxy_server.mjs")?.path ?: "")
        )
        
        for (location in possibleLocations) {
            if (location.exists() && location.isFile) {
                println("[ClaudeCodeNodeSdkWrapper] Found proxy server script at: ${location.absolutePath}")
                return location.absolutePath
            }
        }
        
        // If not found, use the most likely location with a warning
        val defaultPath = "/Users/lewik/code/gromozeka/dev/claude_proxy_server.mjs"
        println("[ClaudeCodeNodeSdkWrapper] WARNING: Proxy server script not found, using default: $defaultPath")
        return defaultPath
    }

    override suspend fun sendMessage(message: String, sessionId: ClaudeSessionUuid) = withContext(Dispatchers.IO) {
        try {
            val proc = process
            println("[ClaudeCodeNodeSdkWrapper] Process alive before sending: ${proc?.isAlive}")

            // Create user message in the same format as other wrappers
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
            println("[ClaudeCodeNodeSdkWrapper] Writing to stdin: $jsonLine")
            println("[ClaudeCodeNodeSdkWrapper] Session ID used: $sessionId")

            val writer = stdinWriter ?: throw IllegalStateException("Process not started")
            writer.write("$jsonLine\n")
            writer.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun sendControlMessage(controlMessage: StreamJsonLine.ControlRequest) = withContext(Dispatchers.IO) {
        try {
            val proc = process
            if (proc == null || !proc.isAlive) {
                throw IllegalStateException("Claude Node SDK proxy process is not running")
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
            println("[ClaudeCodeNodeSdkWrapper] Sending control message: $jsonLine")

            writer.write("$jsonLine\n")
            writer.flush()
        } catch (e: Exception) {
            println("[ClaudeCodeNodeSdkWrapper] Control message failed: ${e.message}")
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
            println("[ClaudeCodeNodeSdkWrapper] Successfully parsed StreamJsonLine: ${parsed.type}")

            // Special logging for control messages
            when (parsed) {
                is StreamJsonLine.ControlResponse -> {
                    println("[ClaudeCodeNodeSdkWrapper] Control response received: request_id=${parsed.response.requestId}, subtype=${parsed.response.subtype}, error=${parsed.response.error}")
                }

                is StreamJsonLine.ControlRequest -> {
                    println("[ClaudeCodeNodeSdkWrapper] Control request parsed: request_id=${parsed.requestId}, subtype=${parsed.request.subtype}")
                }

                else -> {}
            }

            StreamJsonLinePacket(parsed, originalJson)
        } catch (e: SerializationException) {
            println("[ClaudeCodeNodeSdkWrapper] STREAM PARSE ERROR: Failed to deserialize StreamJsonLine")
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
                println("[ClaudeCodeNodeSdkWrapper] SECONDARY ERROR: Failed to parse as JsonObject")
                println("  Exception: ${e.javaClass.simpleName}: ${e.message}")
                StreamJsonLine.System(
                    subtype = "raw_output",
                    data = buildJsonObject { put("raw_line", JsonPrimitive(jsonLine)) },
                    sessionId = null
                )
            }

            StreamJsonLinePacket(fallbackMessage, originalJson)
        } catch (e: Exception) {
            println("[ClaudeCodeNodeSdkWrapper] UNEXPECTED ERROR in parseStreamJsonLine: ${e.javaClass.simpleName}: ${e.message}")
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
                    println("=== CLAUDE NODE SDK STREAM ===")
                    println(line)
                    println("=== END SDK STREAM ===")

                    // Parse and broadcast stream message
                    val streamMessagePacket = parseStreamJsonLine(line)
                    println("[ClaudeCodeNodeSdkWrapper] *** EMITTING STREAM MESSAGE: ${streamMessagePacket.streamMessage.type}")

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

                println("[ClaudeCodeNodeSdkWrapper] STDERR: $line")
            }

        } catch (e: Exception) {
            println("[ClaudeCodeNodeSdkWrapper] Error reading stderr: ${e.message}")
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

    // Reuse the same UserInputMessage data class structure
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
                val text: String
            ) : ContentBlock()

            @Serializable
            @SerialName("image")
            data class ImageBlock(
                val source: ImageSource
            ) : ContentBlock() {
                @Serializable
                data class ImageSource(
                    val type: String = "base64",
                    val media_type: String,
                    val data: String
                )
            }
        }
    }
}