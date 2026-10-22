package com.gromozeka.server

import com.gromozeka.domain.model.AuthenticatedPersonalAccessToken
import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.PersonalAccessTokenService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

internal sealed interface AuthenticatedMcpCaller {
    val user: com.gromozeka.domain.model.User
    data class UserSession(val principal: AuthenticatedUser) : AuthenticatedMcpCaller {
        override val user = principal.user
    }
    data class PersonalToken(val principal: AuthenticatedPersonalAccessToken) : AuthenticatedMcpCaller {
        override val user = principal.user
    }
}

internal suspend fun HttpServletRequest.requireMcpCaller(
    authenticationService: AuthenticationService,
    personalAccessTokenService: PersonalAccessTokenService,
    settings: ServerHttpSettings,
): AuthenticatedMcpCaller {
    if (!isSecureTransport(settings)) {
        throw HttpAuthenticationException(HttpStatus.UPGRADE_REQUIRED, "MCP authentication requires HTTPS")
    }
    requireMcpHttpHeaders(settings.mcp)
    sessionToken()?.let { authenticationService.authenticate(it) }
        ?.let { return AuthenticatedMcpCaller.UserSession(it) }
    val token = getHeader(HttpHeaders.AUTHORIZATION)
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)?.trim()?.takeIf(String::isNotEmpty)
    token?.let { personalAccessTokenService.authenticate(it, PersonalAccessToken.Scope.MCP_CONTROL) }
        ?.let { return AuthenticatedMcpCaller.PersonalToken(it) }
    throw HttpAuthenticationException(HttpStatus.UNAUTHORIZED, "Invalid or missing MCP access token",
        """Bearer realm="gromozeka-mcp"""")
}

internal fun HttpServletRequest.requireMcpHttpHeaders(configuration: McpHttpSecurityConfiguration) {
    fun withPorts(value: String): String {
        if (value.endsWith(":*")) return value
        val uri = runCatching { java.net.URI(if ("://" in value) value else "http://$value") }.getOrNull()
        return if (uri != null && uri.port < 0) "$value:*" else value
    }
    val hosts = configuration.allowedHosts ?: listOf("localhost", "127.0.0.1", "[::1]")
    val origins = configuration.allowedOrigins ?: hosts.map { "http://$it" }
    val validator = io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator.builder()
        .allowedHosts(hosts.map { withPorts(it.lowercase()) })
        .allowedOrigins(origins.map { withPorts(it.lowercase()) })
        .build()
    val origin = getHeader(HttpHeaders.ORIGIN)
    if (origin != null) {
        val authority = runCatching { java.net.URI(origin).rawAuthority }.getOrNull()
        if (!isAllowedBrowserOrigin(origin, authority)) {
            throw HttpAuthenticationException(HttpStatus.FORBIDDEN, "Invalid MCP Origin header")
        }
    }
    try {
        validator.validate(object : io.modelcontextprotocol.server.transport.HeaderAccessor {
            override fun getHeader(name: String): List<String> = java.util.Collections.list(this@requireMcpHttpHeaders.getHeaders(name))
                .map { if (name.equals("Host", true) || name.equals("Origin", true)) it.lowercase() else it }
            override fun getHeaderNames(): List<String> = java.util.Collections.list(this@requireMcpHttpHeaders.headerNames)
        })
    } catch (error: io.modelcontextprotocol.server.transport.ServerTransportSecurityException) {
        throw HttpAuthenticationException(HttpStatus.valueOf(error.statusCode), "Invalid MCP HTTP origin or host")
    }
}

internal const val AUTHENTICATED_MCP_CALLER = "gromozeka.authenticated-mcp-caller"
internal const val AUTHENTICATED_REMOTE_SESSION = "gromozeka.authenticated-remote-session"
internal const val AUTHENTICATED_WORKER_GATEWAY = "gromozeka.authenticated-worker-gateway"
