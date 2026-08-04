package com.gromozeka.domain.model.mcp

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.shared.utils.sha256
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.jvm.JvmInline

@Serializable
data class McpServerConfig(
    val id: McpServerId,
    val displayName: String,
    val workerId: ConversationRuntimeWorkerId,
    val transport: McpServerTransport,
    val timeoutMs: Long = 40_000,
    val allowedTools: Set<String>? = null,
    val excludedTools: Set<String> = emptySet(),
    val forwardGrzConversationContext: Boolean = false,
) {
    init {
        require(displayName.isNotBlank()) { "MCP server display name must not be blank" }
        require(timeoutMs > 0) { "MCP server timeout must be positive" }
        require(allowedTools == null || allowedTools.isNotEmpty()) {
            "MCP server allowed tools must be null or non-empty"
        }
        require(allowedTools.orEmpty().all(String::isNotBlank)) {
            "MCP server allowed tool names must not be blank"
        }
        require(excludedTools.all(String::isNotBlank)) {
            "MCP server excluded tool names must not be blank"
        }
        require(allowedTools.orEmpty().intersect(excludedTools).isEmpty()) {
            "MCP server allowed and excluded tools must not overlap"
        }
    }
}

data class McpTransportValueRemovals(
    val environmentVariables: Set<String> = emptySet(),
    val httpHeaders: Set<String> = emptySet(),
) {
    init {
        require(environmentVariables.none(String::isBlank)) {
            "MCP environment variable names to remove must not be blank"
        }
        require(httpHeaders.none(String::isBlank)) {
            "MCP HTTP header names to remove must not be blank"
        }
        require(httpHeaders.map(String::lowercase).distinct().size == httpHeaders.size) {
            "MCP HTTP header names to remove must be unique ignoring case"
        }
    }
}

@Serializable
sealed interface McpServerTransport {
    @Serializable
    @SerialName("stdio")
    data class Stdio(
        val command: String,
        val arguments: List<String> = emptyList(),
        val environment: Map<String, String> = emptyMap(),
        val ephemeralWorkingDirectory: Boolean = false,
    ) : McpServerTransport {
        init {
            require(command.isNotBlank()) { "MCP stdio command must not be blank" }
            require(arguments.none(String::isBlank)) { "MCP stdio arguments must not be blank" }
            require(environment.keys.none(String::isBlank)) {
                "MCP stdio environment variable names must not be blank"
            }
        }
    }

    @Serializable
    @SerialName("bundled_stdio")
    data class BundledStdio(
        val runtime: BundledMcpRuntime,
        val arguments: List<String> = emptyList(),
        val environment: Map<String, String> = emptyMap(),
        val ephemeralWorkingDirectory: Boolean = false,
    ) : McpServerTransport {
        init {
            require(arguments.none(String::isBlank)) { "Bundled MCP arguments must not be blank" }
            require(environment.keys.none(String::isBlank)) {
                "Bundled MCP environment variable names must not be blank"
            }
        }
    }

    @Serializable
    @SerialName("streamable_http")
    data class StreamableHttp(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : McpServerTransport {
        init {
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "MCP Streamable HTTP URL must use http or https"
            }
            require(headers.keys.none(String::isBlank)) {
                "MCP Streamable HTTP header names must not be blank"
            }
            require(headers.keys.map(String::lowercase).distinct().size == headers.size) {
                "MCP Streamable HTTP header names must be unique ignoring case"
            }
        }
    }
}

@Serializable
enum class BundledMcpRuntime {
    @SerialName("browser_use")
    BROWSER_USE,
}

@Serializable
data class McpServerSnapshot(
    val serverName: String,
    val serverVersion: String,
    val instructions: String? = null,
    val supportsToolsListChanged: Boolean,
    val tools: List<McpToolSnapshot>,
    val fingerprint: String,
    val capturedAt: Instant,
) {
    init {
        require(serverName.isNotBlank()) { "MCP implementation name must not be blank" }
        require(serverVersion.isNotBlank()) { "MCP implementation version must not be blank" }
        require(tools.isNotEmpty()) { "MCP server must expose at least one selected tool" }
        require(tools.map(McpToolSnapshot::remoteName).distinct().size == tools.size) {
            "MCP remote tool names must be unique"
        }
        require(fingerprint == calculateFingerprint(
            serverName = serverName,
            serverVersion = serverVersion,
            instructions = instructions,
            supportsToolsListChanged = supportsToolsListChanged,
            tools = tools,
        )) {
            "MCP server snapshot fingerprint does not match its content"
        }
    }

    companion object {
        fun calculateFingerprint(
            serverName: String,
            serverVersion: String,
            instructions: String?,
            supportsToolsListChanged: Boolean,
            tools: List<McpToolSnapshot>,
        ): String =
            Json.encodeToString(
                FingerprintPayload.serializer(),
                FingerprintPayload(
                    serverName = serverName,
                    serverVersion = serverVersion,
                    instructions = instructions,
                    supportsToolsListChanged = supportsToolsListChanged,
                    tools = tools.sortedBy(McpToolSnapshot::remoteName),
                ),
            ).sha256()
    }

    @Serializable
    private data class FingerprintPayload(
        val serverName: String,
        val serverVersion: String,
        val instructions: String?,
        val supportsToolsListChanged: Boolean,
        val tools: List<McpToolSnapshot>,
    )
}

@Serializable
data class McpToolSnapshot(
    val remoteName: String,
    val description: String,
    val inputSchema: String,
) {
    init {
        require(remoteName.isNotBlank()) { "MCP remote tool name must not be blank" }
        require(runCatching { Json.parseToJsonElement(inputSchema).jsonObject }.isSuccess) {
            "MCP tool input schema must be a JSON object: $remoteName"
        }
    }

    fun toAiToolDefinition(serverId: McpServerId): AiToolDefinition =
        AiToolDefinition(
            name = generatedToolName(serverId, remoteName),
            description = description,
            inputSchema = inputSchema,
            source = serverId.sourceId,
        )
}

@Serializable
data class McpServer(
    val config: McpServerConfig,
    val snapshot: McpServerSnapshot,
    val revision: Long,
    val refreshAvailable: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(revision > 0) { "MCP server revision must be positive" }
        require(updatedAt >= createdAt) { "MCP server update time cannot precede creation" }
        require(
            snapshot.tools
                .map { it.toAiToolDefinition(config.id).name }
                .distinct()
                .size == snapshot.tools.size
        ) {
            "MCP remote tool names collide after Gromozeka name generation"
        }
    }
}

@Serializable
@JvmInline
value class McpServerId(val value: String) {
    init {
        require(value.matches(ID_PATTERN)) {
            "MCP server id must be stable lowercase snake_case: $value"
        }
    }

    val sourceId: String
        get() = "mcp:$value"
}

private fun generatedToolName(
    serverId: McpServerId,
    remoteName: String,
): String {
    val normalizedRemoteName = remoteName.map { character ->
        if (character.isAsciiToolNameCharacter()) character else '_'
    }.joinToString("")
    val directName = "mcp__${serverId.value}__$normalizedRemoteName"
    if (directName.length <= MAX_AI_TOOL_NAME_LENGTH && normalizedRemoteName == remoteName) {
        return directName
    }

    val hash = "${serverId.value}\u0000$remoteName".sha256().take(TOOL_NAME_HASH_LENGTH)
    val readableBudget = MAX_AI_TOOL_NAME_LENGTH -
        MCP_PREFIX.length -
        TOOL_NAME_SEPARATOR.length * 2 -
        hash.length
    val serverPartLength = minOf(serverId.value.length, MAX_SERVER_ID_TOOL_NAME_CHARS)
    val remotePartLength = readableBudget - serverPartLength
    check(remotePartLength > 0)
    return buildString(MAX_AI_TOOL_NAME_LENGTH) {
        append(MCP_PREFIX)
        append(serverId.value.take(serverPartLength))
        append(TOOL_NAME_SEPARATOR)
        append(normalizedRemoteName.take(remotePartLength))
        append(TOOL_NAME_SEPARATOR)
        append(hash)
    }
}

private fun Char.isAsciiToolNameCharacter(): Boolean =
    this in 'a'..'z' ||
        this in 'A'..'Z' ||
        this in '0'..'9' ||
        this == '_' ||
        this == '-'

private val ID_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")
private const val MCP_PREFIX = "mcp__"
private const val TOOL_NAME_SEPARATOR = "__"
private const val MAX_AI_TOOL_NAME_LENGTH = 64
private const val MAX_SERVER_ID_TOOL_NAME_CHARS = 16
private const val TOOL_NAME_HASH_LENGTH = 12
