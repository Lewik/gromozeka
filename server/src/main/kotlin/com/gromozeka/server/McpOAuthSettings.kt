package com.gromozeka.server

import java.net.URI
import java.time.Duration

/** Explicit public origin: never derive OAuth issuer/audience from an untrusted Host header. */
internal data class McpOAuthSettings private constructor(val issuer: String) {
    val resource: String = "$issuer/mcp"
    val protectedResourceMetadata: String = "$issuer/.well-known/oauth-protected-resource/mcp"
    val accessTokenLifetime: Duration = Duration.ofMinutes(10)
    val refreshTokenLifetime: Duration = Duration.ofDays(30)
    val authorizationCodeLifetime: Duration = Duration.ofMinutes(2)

    fun acceptsResource(value: String?): Boolean {
        if (value == null) return false
        return runCatching {
            val uri = URI(value)
            val canonical = URI(uri.scheme?.lowercase(), null, uri.host?.lowercase(), uri.port, uri.rawPath, null, null)
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && canonical.toASCIIString() == resource
        }.getOrDefault(false)
    }

    companion object {
        const val SCOPE = "mcp"
        const val MAX_TOKEN_LENGTH = 2048
        const val MAX_CLIENT_ID_LENGTH = 2048

        fun parse(value: String): McpOAuthSettings {
            val uri = try { URI(value) } catch (_: Exception) { error("Invalid OAuth issuer origin") }
            require(value.length <= 2048 && uri.host != null && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.orEmpty() in setOf("", "/") &&
                (uri.port == -1 || uri.port in 1..65535)) { "OAuth issuer must be an absolute origin without a path, query or credentials" }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host.lowercase()
            require(scheme != null && (scheme == "https" || (scheme == "http" && isOAuthLoopbackHost(host)))) {
                "OAuth issuer requires HTTPS except for an explicit loopback development origin"
            }
            return McpOAuthSettings(URI(scheme, null, host, uri.port, null, null, null).toASCIIString())
        }
    }
}

internal fun isOAuthLoopbackHost(host: String): Boolean =
    host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "[::1]" || host == "::1"
