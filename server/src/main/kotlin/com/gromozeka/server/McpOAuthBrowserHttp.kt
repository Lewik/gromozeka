package com.gromozeka.server

import com.gromozeka.application.service.AuthenticationRejectedException
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.AuthenticationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.HtmlUtils
import java.net.URI

/** Small server-rendered login/consent pages; independent of the Compose/Wasm build. */
@RestController
@ConditionalOnProperty(prefix = "gromozeka.oauth", name = ["issuer"])
internal class McpOAuthBrowserHttp(
    private val authentication: AuthenticationService,
    private val limiter: AuthenticationAttemptLimiter,
    private val settings: McpOAuthSettings,
    private val clients: McpOAuthClientRepository,
    private val authorizations: McpOAuthAuthorizationService,
    private val requestCache: HttpSessionRequestCache,
) {
    @GetMapping("/oauth/login")
    fun loginPage(request: HttpServletRequest, response: HttpServletResponse) {
        if (request.oauthUserOrNull() != null) {
            response.redirectOAuth(continuation(request, response)); return
        }
        response.oauthHtml("Sign in to Gromozeka", loginForm(request))
    }

    @PostMapping("/oauth/login")
    suspend fun login(request: HttpServletRequest, response: HttpServletResponse) {
        val username = request.singleOAuthParameter("username", 255)
        val password = request.singleOAuthParameter("password", 4096).toCharArray()
        try {
            val retry = limiter.retryAfterSeconds(request.remoteAddr, username)
            if (retry != null) {
                response.setHeader("Retry-After", retry.toString())
                response.oauthHtml("Sign in to Gromozeka", loginForm(request, "Too many sign-in attempts. Try again later."), HttpStatus.TOO_MANY_REQUESTS)
                return
            }
            val issued = authentication.login(username, password, "MCP OAuth browser")
            limiter.recordSuccess(username)
            if (request.getSession(false) != null) request.changeSessionId()
            response.setSessionCookie(issued.token, issued.expiresAt.epochSeconds, settings.issuer.startsWith("https://"))
            response.redirectOAuth(continuation(request, response))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AuthenticationRejectedException) {
            limiter.recordFailure(request.remoteAddr, username)
            response.oauthHtml("Sign in to Gromozeka", loginForm(request, "Invalid username or password."), HttpStatus.UNAUTHORIZED)
        } catch (_: IllegalArgumentException) {
            limiter.recordFailure(request.remoteAddr, username)
            response.oauthHtml("Sign in to Gromozeka", loginForm(request, "Invalid sign-in request."), HttpStatus.BAD_REQUEST)
        } catch (_: Exception) {
            response.oauthHtml("Sign in to Gromozeka", loginForm(request, "Sign-in is temporarily unavailable."), HttpStatus.SERVICE_UNAVAILABLE)
        } finally { password.fill('\u0000') }
    }

    @GetMapping("/oauth/consent")
    fun consent(request: HttpServletRequest, response: HttpServletResponse) {
        val user = request.requireOAuthUser()
        val state = request.singleOAuthParameter("state", 500)
        val requestedClientId = request.singleOAuthParameter("client_id", McpOAuthSettings.MAX_CLIENT_ID_LENGTH)
        val authorization = authorizations.findByToken(state, OAuth2TokenType("state"))
        val original = authorization?.getAttribute<OAuth2AuthorizationRequest>(OAuth2AuthorizationRequest::class.java.name)
        val client = authorization?.registeredClientId?.let(clients::findById)
        if (authorization == null || authorization.principalName != user.id.value || client == null ||
            client.clientId != requestedClientId || original == null || !settings.acceptsResource(original.additionalParameters["resource"] as? String)) {
            response.writeOAuthError("invalid_request"); return
        }
        val common = csrfField(request) + hidden("client_id", client.clientId) + hidden("state", state)
        val renewal = if (AuthorizationGrantType.REFRESH_TOKEN in client.authorizationGrantTypes)
            "The app may renew access automatically for up to 30 days. You can revoke this connection at any time."
            else "Access expires after 10 minutes. This client did not request refresh-token support."
        response.oauthHtml("Allow MCP access?", """
            <p>Signed in as <strong>${html(user.displayName)}</strong>.</p>
            <h2>${html(client.clientName)}</h2>
            <p>Client identifier: <code>${html(client.clientId)}</code></p>
            <p>The name is supplied by the client. Verify the identifier before continuing.</p>
            <p>This app requests access to Gromozeka MCP tools within your current permissions.
            This can include reading and changing data, managing resources, and executing tools on your Workers.</p>
            <p>$renewal</p>
            <form method="post" action="/oauth2/authorize" accept-charset="UTF-8">
            $common
            <label><input type="checkbox" name="scope" value="${McpOAuthSettings.SCOPE}" required="required" /> Allow MCP tool access</label>
            <p><button type="submit">Allow access</button></p>
            </form>
            <form method="post" action="/oauth2/authorize" accept-charset="UTF-8">
            $common<button type="submit">Deny</button>
            </form>
        """.trimIndent())
    }

    @GetMapping("/oauth/connections")
    fun connections(request: HttpServletRequest, response: HttpServletResponse) {
        val user = request.requireOAuthUser()
        val rows = authorizations.connections(user.id.value)
        response.oauthHtml("MCP connections", buildString {
            append("<p>Signed in as <strong>${html(user.displayName)}</strong>.</p>")
            append("<p>Up to 100 active connections are shown. Revoking a connection invalidates its current access and refresh tokens.</p>")
            if (rows.isEmpty()) append("<p>No active connections.</p>")
            for (connection in rows) {
                append("<section><h2>${html(connection.clientName)}</h2><p><code>${html(connection.clientId)}</code></p>")
                append("<p>Expires: ${html(connection.expiresAt.toString())}</p>")
                append("<form method=\"post\" action=\"/oauth/connections/revoke\">")
                append(csrfField(request)); append(hidden("authorization_id", connection.id))
                append("<button type=\"submit\">Revoke connection</button></form></section>")
            }
        })
    }

    @PostMapping("/oauth/connections/revoke")
    fun revoke(request: HttpServletRequest, response: HttpServletResponse) {
        val user = request.requireOAuthUser()
        authorizations.revokeOwned(user.id.value, request.singleOAuthParameter("authorization_id", 100))
        response.redirectOAuth("/oauth/connections")
    }

    @GetMapping("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")
    fun protectedResource(response: HttpServletResponse) {
        response.writeJson(buildJsonObject {
            put("resource", settings.resource)
            put("resource_name", "Gromozeka MCP")
            put("authorization_servers", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(settings.issuer)) })
            put("scopes_supported", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(McpOAuthSettings.SCOPE)) })
            put("bearer_methods_supported", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("header")) })
        })
    }

    private fun continuation(request: HttpServletRequest, response: HttpServletResponse): String {
        val saved = requestCache.getRequest(request, response)
        requestCache.removeRequest(request, response)
        val uri = saved?.redirectUrl?.let { runCatching { URI(it) }.getOrNull() }
        // Only resume our own authorization endpoint; never accept a user-provided return URL.
        return if (uri?.rawPath == "/oauth2/authorize" && uri.rawFragment == null)
            uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "") else "/oauth/connections"
    }

    private fun loginForm(request: HttpServletRequest, error: String? = null): String = """
        <p>Sign in here to authorize an external MCP app. Your password is not sent to that app.</p>
        ${error?.let { "<p role=\"alert\">${html(it)}</p>" } ?: ""}
        <form method="post" action="/oauth/login" accept-charset="UTF-8">
        ${csrfField(request)}
        <p><label>Username <input name="username" autocomplete="username" required="required" maxlength="255" /></label></p>
        <p><label>Password <input type="password" name="password" autocomplete="current-password" required="required" maxlength="4096" /></label></p>
        <button type="submit">Sign in</button>
        </form>
        <p><a href="/oauth/login">Continue with an existing browser session</a></p>
    """.trimIndent()
}

private fun HttpServletRequest.oauthUserOrNull(): User? = getAttribute(MCP_OAUTH_USER_ATTRIBUTE) as? User
private fun HttpServletRequest.requireOAuthUser(): User = oauthUserOrNull() ?: throw MissingAuthenticationException()
private fun html(value: String): String = HtmlUtils.htmlEscape(value, "UTF-8")
private fun hidden(name: String, value: String) = "<input type=\"hidden\" name=\"${html(name)}\" value=\"${html(value)}\" />"
private fun csrfField(request: HttpServletRequest): String {
    val token = request.getAttribute(CsrfToken::class.java.name) as? CsrfToken ?: error("OAuth CSRF token is unavailable")
    return hidden(token.parameterName, token.token)
}
private fun HttpServletResponse.redirectOAuth(path: String) {
    require(path.startsWith('/') && !path.startsWith("//"))
    status = HttpStatus.SEE_OTHER.value(); setHeader("Location", path)
}
private fun HttpServletResponse.oauthHtml(title: String, body: String, status: HttpStatus = HttpStatus.OK) =
    writeText("<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\" /><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" /><title>${html(title)}</title></head><body><h1>${html(title)}</h1>$body<p><a href=\"/oauth/connections\">Manage MCP connections</a> · <a href=\"/\">Gromozeka</a></p></body></html>", status, "text/html")
