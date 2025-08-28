package com.gromozeka.bot.services

import com.gromozeka.bot.model.AgentDefinition
import com.gromozeka.bot.model.ClaudeCodeStreamJsonLine
import com.gromozeka.bot.model.StreamJsonLinePacket
import klog.KLoggers

import com.gromozeka.bot.settings.AppMode
import com.gromozeka.bot.settings.ResponseFormat
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// No longer a Spring @Component - created per Session for proper isolation
class ClaudeCodeStreamingWrapper(
    private val settingsService: SettingsService,
) : ClaudeWrapper {
    private val log = KLoggers.logger(this)
    
    companion object {
        /**
         * Attempts to auto-detect Claude CLI installation path
         * Tries multiple strategies to find the executable
         */
        fun detectClaudePath(): String? {
            val logger = KLoggers.logger(ClaudeCodeStreamingWrapper::class)
            
            // Strategy 1: Try to find via login shell (works for most installations)
            try {
                val process = ProcessBuilder("/bin/zsh", "-l", "-c", "command -v claude")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                
                val result = process.inputStream.bufferedReader().readText().trim()
                if (result.isNotEmpty() && File(result).exists()) {
                    logger.info { "Found Claude CLI via zsh login shell: $result" }
                    return result
                }
            } catch (e: Exception) {
                logger.debug { "Failed to find Claude via zsh: ${e.message}" }
            }
            
            // Strategy 2: Check common installation paths
            val commonPaths = listOf(
                // NVM installations (various node versions)
                "/Users/${System.getProperty("user.name")}/.nvm/versions/node/v20.10.0/bin/claude",
                "/Users/${System.getProperty("user.name")}/.nvm/versions/node/v22.0.0/bin/claude",
                "/Users/${System.getProperty("user.name")}/.nvm/versions/node/v21.0.0/bin/claude",
                "/Users/${System.getProperty("user.name")}/.nvm/versions/node/v19.0.0/bin/claude",
                "/Users/${System.getProperty("user.name")}/.nvm/versions/node/v18.0.0/bin/claude",
                
                // Homebrew installations
                "/opt/homebrew/bin/claude",
                "/usr/local/bin/claude",
                
                // Direct npm global installation
                "/Users/${System.getProperty("user.name")}/.npm-global/bin/claude",
                "/Users/${System.getProperty("user.name")}/node_modules/.bin/claude",
                
                // System-wide installations
                "/usr/bin/claude",
                "/bin/claude"
            )
            
            for (path in commonPaths) {
                if (File(path).exists() && File(path).canExecute()) {
                    logger.info { "Found Claude CLI at common path: $path" }
                    return path
                }
            }
            
            // Strategy 3: Try to find any claude executable in NVM directory
            val nvmDir = File("/Users/${System.getProperty("user.name")}/.nvm/versions/node")
            if (nvmDir.exists() && nvmDir.isDirectory) {
                nvmDir.walkTopDown()
                    .filter { it.name == "claude" && it.canExecute() }
                    .firstOrNull()?.let { claudeFile ->
                        logger.info { "Found Claude CLI in NVM directory: ${claudeFile.absolutePath}" }
                        return claudeFile.absolutePath
                    }
            }
            
            logger.warn { "Could not auto-detect Claude CLI installation path" }
            return null
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val json = Json {
        explicitNulls = true
        encodeDefaults = true
    }

    private fun loadSystemPrompt(
        responseFormat: ResponseFormat,
        agentPrompt: String,
    ): String {
        // Load format-specific prompt
        val basePrompt = SystemPromptLoader.loadPrompt(responseFormat, agentPrompt)
        log.debug("Loaded prompt for format: $responseFormat")

        var prompt = if (settingsService.mode == AppMode.DEV) {
            log.debug("DEV mode detected - loading additional DEV prompt")
            val devPrompt = this::class.java.getResourceAsStream("/dev-mode-prompt.md")
                ?.bufferedReader()
                ?.readText()
                ?: ""

            if (devPrompt.isNotEmpty()) {
                log.debug("DEV prompt loaded successfully (${devPrompt.length} chars)")
                "$basePrompt\n\n$devPrompt"
            } else {
                log.warn("DEV prompt file empty or not found")
                basePrompt
            }
        } else {
            log.debug("PROD mode - using base prompt only")
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
            log.info(
                "Added current time to prompt: ${now.format(dateFormatter)} ${
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
        appendSystemPrompt: String,
        mcpConfigPath: String?,
        tabId: String?,
        agentDefinition: AgentDefinition
    ) = withContext(Dispatchers.IO) {
        try {
            log.info("=== STARTING CLAUDE CODE STREAMING WRAPPER ===")


            val systemPrompt = (loadSystemPrompt(responseFormat, agentDefinition.prompt) + appendSystemPrompt).replace("\"", "\\\"")

            // NOTE: In stream-json mode, Claude Code sends multiple init messages - this is NORMAL behavior.
            // We confirmed this by testing claude-code-sdk-python: each user message triggers a new init.
            // The session ID remains the same across all init messages.
            // See docs/claude-code-streaming-behavior.md for detailed analysis.
            
            // Build claude command arguments as list for proper shell escaping
            // Use Claude CLI path from settings or fallback to default
            val claudePath = settingsService.settings.claudeCliPath
                ?: "/Users/lewik/.nvm/versions/node/v20.10.0/bin/claude" // Hardcoded fallback if not configured
            
            log.info { "Using Claude CLI path: $claudePath" }
            
            val claudeArgs = mutableListOf(
                claudePath,
                "--output-format", "stream-json",
                "--input-format", "stream-json",
                "--verbose",
                "--permission-mode", "acceptEdits",
                "--append-system-prompt", systemPrompt
            )

            // Add model parameter if specified
            if (!model.isNullOrBlank()) {
                claudeArgs.add("--model")
                claudeArgs.add(model)
                log.info("Using model: $model")
            }

            // Add resume parameter if specified
            if (resumeSessionId != null) {
                claudeArgs.add("--resume")
                claudeArgs.add(resumeSessionId.value)
                log.info("Resuming session: $resumeSessionId")
            }

            // Add MCP configuration if provided by session
            if (!mcpConfigPath.isNullOrBlank()) {
                val mcpConfigFile = File(mcpConfigPath)
                if (mcpConfigFile.exists()) {
                    claudeArgs.add("--mcp-config")
                    claudeArgs.add(mcpConfigPath)
                    claudeArgs.add("--allowedTools")
                    claudeArgs.add("mcp__gromozeka-self-control")
                    log.info("Added session MCP config: $mcpConfigPath")
                } else {
                    log.warn("Session MCP config not found: $mcpConfigPath")
                }
            } else {
                log.debug("No MCP config provided - session will run without MCP")
            }

            // Execute through zsh to inherit user PATH from .zshrc (fixes macOS app PATH issue)
            // Properly quote arguments for shell execution
            val claudeCommand = claudeArgs.joinToString(" ") { arg ->
                // Quote arguments containing spaces or special characters
                if (arg.contains(' ') || arg.contains('"') || arg.contains('\'') || arg.contains('$')) {
                    "'${arg.replace("'", "'\\''")}'"
                } else {
                    arg
                }
            }
            val command = listOf("/bin/zsh", "-l", "-c", claudeCommand)

            // Truncate system prompt for cleaner logs
            val truncatedClaudeCommand = if (claudeCommand.length > 500) {
                claudeCommand.substring(0, 200) + "<COMMAND_TRUNCATED_${claudeCommand.length}_CHARS>" + 
                claudeCommand.substring(claudeCommand.length - 100)
            } else {
                claudeCommand
            }
            log.info { "EXECUTING COMMAND: /bin/zsh -l -c '$truncatedClaudeCommand'" }
            log.debug("FULL COMMAND: ${command.joinToString(" ")}")

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
            log.info { "Process started with PID: ${proc.pid()}" }

            stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))
            stdoutReader = BufferedReader(proc.inputStream.reader())
            stderrReader = BufferedReader(proc.errorStream.reader())

            readJob = scope.launchReadOutputStream()
            stderrJob = scope.launchReadErrorStream()

        } catch (e: Exception) {
            log.error(e) { "Failed to start Claude Code process" }
        }
    }

    override suspend fun sendMessage(streamMessage: ClaudeCodeStreamJsonLine) = withContext(Dispatchers.IO) {
        try {
            val proc = process
            log.debug("Process alive before sending: ${proc?.isAlive}")

            // Serialize the complete stream message directly
            val jsonLine = json.encodeToString(streamMessage)
            log.debug("Writing to stdin: $jsonLine")

            // Extract session ID for logging (if it's a User message)
            val sessionId = when (streamMessage) {
                is ClaudeCodeStreamJsonLine.User -> streamMessage.sessionId
                is ClaudeCodeStreamJsonLine.Assistant -> streamMessage.sessionId
                is ClaudeCodeStreamJsonLine.System -> streamMessage.sessionId
                is ClaudeCodeStreamJsonLine.Result -> streamMessage.sessionId
                else -> throw IllegalStateException("Can't send message to $streamMessage: no sessionId")
            }
            log.debug("Session ID used: $sessionId")

            val writer = stdinWriter ?: throw IllegalStateException("Process not started")
            writer.write("$jsonLine\n")
            writer.flush()
        } catch (e: Exception) {
            log.error(e) { "Failed to send message" }
        }
    }

    override suspend fun sendControlMessage(controlMessage: ClaudeCodeStreamJsonLine.ControlRequest) =
        withContext(Dispatchers.IO) {
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
                log.debug("Sending control message: $jsonLine")

                writer.write("$jsonLine\n")
                writer.flush()
            } catch (e: Exception) {
                log.error(e) { "Control message failed" }
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
            val parsed = Json.decodeFromString<ClaudeCodeStreamJsonLine>(jsonLine)
            log.debug("Successfully parsed StreamJsonLine: ${parsed::class.simpleName}")

            // Special logging for control messages
            when (parsed) {
                is ClaudeCodeStreamJsonLine.ControlResponse -> {
                    log.debug("Control response received: request_id=${parsed.response.requestId}, subtype=${parsed.response.subtype}, error=${parsed.response.error}")
                }

                is ClaudeCodeStreamJsonLine.ControlRequest -> {
                    log.debug("Control request parsed: request_id=${parsed.requestId}, subtype=${parsed.request.subtype}")
                }

                else -> {}
            }

            StreamJsonLinePacket(parsed, originalJson)
        } catch (e: SerializationException) {
            log.error(e) { "STREAM PARSE ERROR: Failed to deserialize StreamJsonLine" }
            log.debug("JSON line: $jsonLine")
            val fallbackMessage = try {
                ClaudeCodeStreamJsonLine.System(
                    subtype = "parse_error",
                    data = Json.parseToJsonElement(jsonLine) as JsonObject,
                    sessionId = null
                )
            } catch (e: Exception) {
                log.error(e) { "SECONDARY ERROR: Failed to parse as JsonObject" }
                ClaudeCodeStreamJsonLine.System(
                    subtype = "raw_output",
                    data = buildJsonObject { put("raw_line", JsonPrimitive(jsonLine)) },
                    sessionId = null
                )
            }

            StreamJsonLinePacket(fallbackMessage, originalJson)
        } catch (e: Exception) {
            log.error(e) { "UNEXPECTED ERROR in parseStreamJsonLine" }
            log.debug("JSON line: $jsonLine")
            val fallbackMessage = ClaudeCodeStreamJsonLine.System(
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
                    log.debug("=== CLAUDE CODE LIVE STREAM ===")
                    log.debug(line)
                    log.debug("=== END LIVE STREAM ===")

                    // Parse and broadcast stream message
                    val streamMessagePacket = parseStreamJsonLine(line)
                    log.debug("*** EMITTING STREAM MESSAGE: ${streamMessagePacket.streamMessage::class.simpleName}")

                    _streamMessages.emit(streamMessagePacket)
                } catch (e: Exception) {
                    log.error(e) { "Error processing stream line" }
                } finally {
                    // Log raw JSONL line after all processing (guaranteed)
                    streamLogger?.logLine(line)
                }
            }

        } catch (e: Exception) {
            log.error(e) { "Error reading stdout stream" }
        }
    }

    private suspend fun CoroutineScope.launchReadErrorStream() = launch {
        try {
            val reader = stderrReader
            require(reader != null) { "Stderr reader was null" }

            while (true) {
                val line = reader.readLine() ?: break
                if (line.trim().isEmpty()) continue

                log.warn { "STDERR: $line" }
            }

        } catch (e: Exception) {
            log.error(e) { "Error reading stderr" }
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
            log.error(e) { "Error stopping Claude Code process" }
        } finally {
            streamLogger = null
        }
    }
}

