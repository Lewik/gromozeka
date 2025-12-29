package com.gromozeka.infrastructure.ai.config.mcp

import com.fasterxml.jackson.annotation.JsonProperty

data class McpConfig(
    @JsonProperty("mcpServers")
    val mcpServers: Map<String, ServerConfig> = emptyMap()
)

data class ServerConfig(
    @JsonProperty("command")
    val command: String? = null,

    @JsonProperty("args")
    val args: List<String>? = null,

    @JsonProperty("env")
    val env: Map<String, String>? = null,

    @JsonProperty("url")
    val url: String? = null,

    @JsonProperty("sseEndpoint")
    val sseEndpoint: String? = null,

    @JsonProperty("headers")
    val headers: Map<String, String>? = null,

    @JsonProperty("timeout")
    val timeout: Int? = null,

    @JsonProperty("disabled")
    val disabled: Boolean = false,

    @JsonProperty("excludedTools")
    val excludedTools: List<String>? = null
) {
    val transportType: TransportType
        get() = when {
            command != null -> TransportType.STDIO
            url != null -> TransportType.SSE
            else -> TransportType.UNKNOWN
        }
}

enum class TransportType {
    STDIO,
    SSE,
    UNKNOWN
}
