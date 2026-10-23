package com.gromozeka.server

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator
import org.springframework.security.web.authentication.AuthenticationConverter
import java.time.Clock
import java.util.Base64

/** Spring's normal public-client converter supports the PKCE code exchange only. */
internal class McpOAuthPublicClientConverter : AuthenticationConverter {
    override fun convert(request: HttpServletRequest): Authentication? {
        if (request.method != "POST" || request.getHeader("Authorization") != null ||
            request.getParameter("client_secret") != null) return null
        val revoke = request.requestURI == "/oauth2/revoke"
        val refresh = request.requestURI == "/oauth2/token" && request.getParameter("grant_type") == "refresh_token"
        if (!refresh && !revoke) return null
        if (refresh) request.singleOAuthParameter("grant_type", 32)
        val clientId = request.singleOAuthParameter("client_id", McpOAuthSettings.MAX_CLIENT_ID_LENGTH)
        val token = request.singleOAuthParameter(if (refresh) "refresh_token" else "token", McpOAuthSettings.MAX_TOKEN_LENGTH)
        return PublicMcpClientRequest(clientId, token, revoke)
    }
}

/** Client identification is not user authentication. The framework still validates the
 * refresh token and its exact client binding, scopes, activity and grant type. */
internal class McpOAuthPublicClientProvider(
    private val clients: McpOAuthClientRepository,
    private val authorizations: McpOAuthAuthorizationService,
) : AuthenticationProvider {
    override fun supports(authentication: Class<*>): Boolean = PublicMcpClientRequest::class.java.isAssignableFrom(authentication)

    override fun authenticate(authentication: Authentication): Authentication? {
        val request = authentication as? PublicMcpClientRequest ?: return null
        val client = clients.findStoredByClientId(request.clientId)
            ?: throw oauthFailure("invalid_client", "Unknown client")
        if (client.clientAuthenticationMethods != setOf(ClientAuthenticationMethod.NONE)) {
            throw oauthFailure("invalid_client", "Unsupported client authentication")
        }
        if (!request.revoke && AuthorizationGrantType.REFRESH_TOKEN !in client.authorizationGrantTypes) {
            throw oauthFailure("unauthorized_client", "Refresh is not enabled for this client")
        }
        if (request.revoke) authorizations.revokeTokenForClient(request.presentedToken, client.id)
        return OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null)
    }
}

/** Uses the framework's secure random key generator; adds issuance for public clients.
 * Rotation itself and token responses remain in the framework refresh-grant provider. */
internal class McpOAuthRefreshTokenGenerator(private val clock: Clock) : OAuth2TokenGenerator<OAuth2RefreshToken> {
    private val keys = Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96)
    override fun generate(context: OAuth2TokenContext): OAuth2RefreshToken? {
        if (context.tokenType != OAuth2TokenType.REFRESH_TOKEN ||
            AuthorizationGrantType.REFRESH_TOKEN !in context.registeredClient.authorizationGrantTypes) return null
        val now = clock.instant()
        // A successor must not extend the original grant's maximum refresh lifetime.
        val expiresAt = context.authorization?.refreshToken?.token?.expiresAt
            ?: now.plus(context.registeredClient.tokenSettings.refreshTokenTimeToLive)
        if (!expiresAt.isAfter(now)) throw oauthFailure("invalid_grant", "Authorization expired")
        return OAuth2RefreshToken(keys.generateKey(), now, expiresAt)
    }
}

private class PublicMcpClientRequest(val clientId: String, val presentedToken: String, val revoke: Boolean) :
    AbstractAuthenticationToken(emptyList()) {
    override fun getPrincipal(): Any = clientId
    override fun getCredentials(): Any? = null
}

internal fun HttpServletRequest.singleOAuthParameter(name: String, maxLength: Int): String {
    val values = getParameterValues(name)
    if (values == null || values.size != 1 || values[0].isBlank() || values[0].length > maxLength) {
        throw oauthFailure("invalid_request", "Invalid OAuth request parameters")
    }
    return values[0]
}
