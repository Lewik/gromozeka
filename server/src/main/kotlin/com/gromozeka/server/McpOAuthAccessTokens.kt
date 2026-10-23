package com.gromozeka.server

import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.UserDirectoryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import java.time.Clock

internal data class McpOAuthPrincipal(val user: User, val authorizationId: String, val clientId: String)

internal class McpOAuthAccessTokens(
    private val authorizations: McpOAuthAuthorizationService,
    private val clients: McpOAuthClientRepository,
    private val users: UserDirectoryService,
    val settings: McpOAuthSettings,
    private val clock: Clock,
) {
    suspend fun authenticate(token: String): McpOAuthPrincipal? = withContext(Dispatchers.IO) {
        if (token.length !in 1..McpOAuthSettings.MAX_TOKEN_LENGTH) return@withContext null
        val authorization = authorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN) ?: return@withContext null
        val access = authorization.accessToken ?: return@withContext null
        if (access.isInvalidated || access.token.tokenType != OAuth2AccessToken.TokenType.BEARER ||
            access.token.expiresAt?.isAfter(clock.instant()) != true) return@withContext null
        val claims = access.claims ?: return@withContext null
        // The framework JDBC claim converter restores iss as URL. Compare its textual
        // representation, never URL.equals (which can perform DNS resolution).
        val issuer = when (val value = claims["iss"]) {
            is String -> value
            is java.net.URL -> value.toExternalForm()
            else -> null
        }
        if (claims["sub"] != authorization.principalName || issuer != settings.issuer || (claims["aud"] as? Collection<*>)?.toList() != listOf(settings.resource)) {
            return@withContext null
        }
        val user = users.findActiveById(User.Id(authorization.principalName))?.takeIf { it.canLogin }
            ?: return@withContext null
        if (McpOAuthSettings.SCOPE !in access.token.scopes) {
            throw HttpAuthenticationException(HttpStatus.FORBIDDEN, "Insufficient OAuth scope", challenge("insufficient_scope"))
        }
        val client = clients.findById(authorization.registeredClientId) ?: return@withContext null
        McpOAuthPrincipal(user, authorization.id, client.clientId)
    }

    fun challenge(error: String? = null): String = buildString {
        append("Bearer realm=\"gromozeka-mcp\", resource_metadata=\"")
        append(settings.protectedResourceMetadata)
        append("\", scope=\""); append(McpOAuthSettings.SCOPE); append('"')
        if (error != null) {
            require(error in setOf("invalid_token", "insufficient_scope"))
            append(", error=\""); append(error); append('"')
        }
    }
}
