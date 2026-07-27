package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.service.AiConfigurationProvider
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service
import java.io.File
import kotlin.time.Duration.Companion.seconds

@Service
internal class ClaudeCodeNativeWebToolClient(
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val executor: ClaudeCodeNativeToolExecutor,
) {
    fun isAvailable(tool: ClaudeCodeNativeTool): Boolean =
        runCatching {
            val runtime = resolveRuntime(tool)
            executableExists(runtime.connection.executablePath)
        }.getOrDefault(false)

    suspend fun execute(
        tool: ClaudeCodeNativeTool,
        input: JsonObject,
    ): JsonObject {
        val runtime = resolveRuntime(tool)
        val invocation = ClaudeCodeNativeToolInvocation(tool, input)
        val command = ClaudeCodeCommand(
            connectionId = runtime.connection.id.value,
            executablePath = runtime.connection.executablePath,
            modelName = runtime.configuration.providerModelId,
            workspaceDirectory = null,
            systemPrompt = systemPrompt(tool),
            userPrompt = userPrompt(invocation),
            jsonSchema = null,
            effort = runtime.configuration.defaultParameters.reasoning?.effort,
            reasoningMode = runtime.configuration.defaultParameters.reasoning?.mode,
            resumeSessionId = null,
            noSessionPersistence = true,
            nativeTools = setOf(tool),
        )
        val timeout = (runtime.configuration.defaultParameters.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS).seconds
        val response = withTimeout(timeout) {
            executor.executeNativeTool(command, invocation)
        }
        return buildJsonObject {
            put("success", true)
            put("provider", "claude_code")
            put("model_configuration_id", runtime.configuration.id.value)
            put("tool", response.tool.cliName)
            put("input", response.input)
            put("result", response.result)
        }
    }

    private fun resolveRuntime(tool: ClaudeCodeNativeTool): ResolvedRuntime {
        val catalog = aiConfigurationProvider.catalog
        val settings = catalog.webTools.claudeCode
        val enabled = when (tool) {
            ClaudeCodeNativeTool.WEB_SEARCH -> settings.searchEnabled
            ClaudeCodeNativeTool.WEB_FETCH -> settings.fetchEnabled
        }
        require(enabled) { "Claude Code ${tool.cliName} is disabled" }
        val configurationId = requireNotNull(settings.modelConfigurationId) {
            "Claude Code web tool model configuration is not selected"
        }
        val configuration = catalog.modelConfigurations.singleOrNull { it.id == configurationId }
            ?: error("Claude Code web tool model configuration not found: ${configurationId.value}")
        require(configuration.enabled) {
            "Claude Code web tool model configuration is disabled: ${configuration.id.value}"
        }
        val connection = catalog.connectionFor(configuration) as? AiConnection.ClaudeCode
            ?: error("Claude Code web tool model must use a Claude Code connection")
        require(connection.enabled) {
            "Claude Code web tool connection is disabled: ${connection.id.value}"
        }
        return ResolvedRuntime(connection, configuration)
    }

    private fun systemPrompt(tool: ClaudeCodeNativeTool): String =
        """
        You are an exact adapter for the Claude Code native ${tool.cliName} tool.
        Invoke ${tool.cliName} exactly once using exactly the JSON arguments supplied by the user.
        Do not invoke any other tool. Do not change, infer, normalize, retry, or follow redirects.
        Gromozeka captures the native tool result directly, so do not produce a prose answer.
        """.trimIndent()

    private fun userPrompt(invocation: ClaudeCodeNativeToolInvocation): String =
        "Invoke ${invocation.tool.cliName} exactly once with these exact arguments: ${invocation.input}"

    private fun executableExists(executablePath: String): Boolean {
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val executable = File(executablePath)
        if (executable.isAbsolute || executablePath.contains(File.separatorChar)) {
            return executable.isFile && (isWindows || executable.canExecute())
        }
        val pathEntries = System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
        val extensions = if (isWindows) {
            System.getenv("PATHEXT").orEmpty()
                .split(';')
                .filter(String::isNotBlank)
                .ifEmpty { listOf(".EXE", ".CMD", ".BAT") }
        } else {
            listOf("")
        }
        val executableNames = (listOf(executablePath) + extensions.map { executablePath + it }).distinct()
        return pathEntries.any { directory ->
            executableNames.any { executableName ->
                File(directory, executableName).let { candidate ->
                    candidate.isFile && (isWindows || candidate.canExecute())
                }
            }
        }
    }

    private data class ResolvedRuntime(
        val connection: AiConnection.ClaudeCode,
        val configuration: AiModelConfiguration,
    )

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 300
    }
}
