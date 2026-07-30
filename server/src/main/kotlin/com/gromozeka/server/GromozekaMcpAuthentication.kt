package com.gromozeka.server

import com.gromozeka.domain.model.AuthenticatedPersonalAccessToken
import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.PersonalAccessTokenService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.util.AttributeKey

internal sealed interface AuthenticatedMcpCaller {
    val user: com.gromozeka.domain.model.User

    data class UserSession(
        val principal: AuthenticatedUser,
    ) : AuthenticatedMcpCaller {
        override val user = principal.user
    }

    data class PersonalToken(
        val principal: AuthenticatedPersonalAccessToken,
    ) : AuthenticatedMcpCaller {
        override val user = principal.user
    }
}

internal fun Application.installMcpAuthentication(
    authenticationService: AuthenticationService,
    personalAccessTokenService: PersonalAccessTokenService,
) {
    install(
        createApplicationPlugin("GromozekaMcpAuthentication") {
            onCall { call ->
                val requiredScope = call.request.path().requiredMcpScope() ?: return@onCall
                if (!call.isSecureTransport()) {
                    throw HttpAuthenticationException(
                        HttpStatusCode.UpgradeRequired,
                        "MCP authentication requires HTTPS",
                    )
                }
                val caller = call.authenticateMcpCaller(
                    authenticationService = authenticationService,
                    personalAccessTokenService = personalAccessTokenService,
                    requiredScope = requiredScope,
                ) ?: throw HttpAuthenticationException(
                    HttpStatusCode.Unauthorized,
                    "Invalid or missing MCP access token",
                    """Bearer realm="gromozeka-mcp"""",
                )
                call.attributes.put(authenticatedMcpCallerKey, caller)
            }
        }
    )
}

private suspend fun ApplicationCall.authenticateMcpCaller(
    authenticationService: AuthenticationService,
    personalAccessTokenService: PersonalAccessTokenService,
    requiredScope: PersonalAccessToken.Scope,
): AuthenticatedMcpCaller? {
    request.cookies[SESSION_COOKIE_NAME]
        ?.let { authenticationService.authenticate(it) }
        ?.let { return AuthenticatedMcpCaller.UserSession(it) }

    val bearerToken = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return personalAccessTokenService.authenticate(bearerToken, requiredScope)
        ?.let(AuthenticatedMcpCaller::PersonalToken)
}

private fun String.requiredMcpScope(): PersonalAccessToken.Scope? =
    when {
        this == CONTROL_MCP_PATH || startsWith("$CONTROL_MCP_PATH/") ->
            PersonalAccessToken.Scope.MCP_CONTROL
        this == MEMORY_MCP_PATH || startsWith("$MEMORY_MCP_PATH/") ->
            PersonalAccessToken.Scope.MCP_MEMORY
        else -> null
    }

internal val authenticatedMcpCallerKey =
    AttributeKey<AuthenticatedMcpCaller>("gromozeka-authenticated-mcp-caller")

private const val BEARER_PREFIX = "Bearer "
private const val MEMORY_MCP_PATH = "/mcp"
private const val CONTROL_MCP_PATH = "/mcp/control"
