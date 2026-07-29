package com.gromozeka.server

import com.gromozeka.domain.model.AuthenticatedPersonalAccessToken
import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.PersonalAccessTokenService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.path
import io.ktor.server.response.respondText
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
    install(StatusPages) {
        exception<McpAuthenticationException> { call, error ->
            if (error.status == HttpStatusCode.Unauthorized) {
                call.response.headers.append(
                    HttpHeaders.WWWAuthenticate,
                    """Bearer realm="gromozeka-mcp"""",
                )
            }
            call.respondText(
                """{"error":"${error.publicMessage}"}""",
                ContentType.Application.Json,
                error.status,
            )
        }
    }

    install(
        createApplicationPlugin("GromozekaMcpAuthentication") {
            onCall { call ->
                val requiredScope = call.request.path().requiredMcpScope() ?: return@onCall
                if (!call.isSecureTransport()) {
                    throw McpAuthenticationException(
                        HttpStatusCode.UpgradeRequired,
                        "MCP authentication requires HTTPS",
                    )
                }
                val caller = call.authenticateMcpCaller(
                    authenticationService = authenticationService,
                    personalAccessTokenService = personalAccessTokenService,
                    requiredScope = requiredScope,
                ) ?: throw McpAuthenticationException(
                    HttpStatusCode.Unauthorized,
                    "Invalid or missing MCP access token",
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

private class McpAuthenticationException(
    val status: HttpStatusCode,
    val publicMessage: String,
) : RuntimeException(publicMessage)

internal val authenticatedMcpCallerKey =
    AttributeKey<AuthenticatedMcpCaller>("gromozeka-authenticated-mcp-caller")

private const val BEARER_PREFIX = "Bearer "
private const val MEMORY_MCP_PATH = "/mcp"
private const val CONTROL_MCP_PATH = "/mcp/control"
