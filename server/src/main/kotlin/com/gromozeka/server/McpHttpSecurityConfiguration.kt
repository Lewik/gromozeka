package com.gromozeka.server

internal data class McpHttpSecurityConfiguration(
    val allowedHosts: List<String>?,
    val allowedOrigins: List<String>?,
)

internal fun resolveMcpHttpSecurityConfiguration(configuredAllowedHosts: String?): McpHttpSecurityConfiguration {
    if (configuredAllowedHosts == null) {
        return McpHttpSecurityConfiguration(
            allowedHosts = null,
            allowedOrigins = null,
        )
    }

    val allowedHosts = configuredAllowedHosts
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    require(allowedHosts.isNotEmpty()) {
        "GROMOZEKA_MCP_ALLOWED_HOSTS must contain at least one hostname when configured"
    }

    return McpHttpSecurityConfiguration(
        allowedHosts = allowedHosts,
        allowedOrigins = allowedHosts.map { host -> "http://$host" },
    )
}
