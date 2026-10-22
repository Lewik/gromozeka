package com.gromozeka.server

import com.gromozeka.application.service.AuthenticationRejectedException
import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.remote.protocol.AuthenticationErrorCode
import com.gromozeka.remote.protocol.AuthenticationErrorResponse
import com.gromozeka.remote.protocol.AuthenticationSessionResponse
import com.gromozeka.remote.protocol.AuthenticationStatusResponse
import com.gromozeka.remote.protocol.BootstrapUserRequest
import com.gromozeka.remote.protocol.LoginRequest
import com.gromozeka.remote.protocol.toAuthenticatedUserView
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.time.Clock

internal const val SESSION_COOKIE_NAME = "gromozeka_session"
private val authenticationJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal data class AuthenticatedRemoteSession(val token: String, val principal: AuthenticatedUser)
internal class MissingAuthenticationException : RuntimeException("Authentication required")

@RestController
internal class GromozekaAuthenticationHttp(
    private val authenticationService: AuthenticationService,
    private val attemptLimiter: AuthenticationAttemptLimiter,
    private val settings: ServerHttpSettings,
) {
    @GetMapping("/auth/status")
    suspend fun status(request: HttpServletRequest, response: HttpServletResponse) {
        val principal = request.authenticateOrNull(authenticationService)
        response.respondAuthenticationJson(AuthenticationStatusResponse(
            initialized = authenticationService.hasUsers(),
            authenticatedUser = principal?.user?.toAuthenticatedUserView(),
        ))
    }

    @PostMapping("/auth/bootstrap")
    suspend fun bootstrap(request: HttpServletRequest, response: HttpServletResponse) {
        if (!request.requireAllowedAuthenticationOrigin(response) ||
            !request.requireSecureAuthenticationTransport(response, settings)) return
        val body = request.receiveAuthenticationRequest<BootstrapUserRequest>(response) ?: return
        val password = body.password.toCharArray()
        try {
            val session = authenticationService.createFirstUser(body.bootstrapToken, body.username,
                body.displayName, password, body.clientLabel)
            response.setSessionCookie(session.token, session.expiresAt.epochSeconds, settings.secureCookie)
            response.respondAuthenticationJson(AuthenticationSessionResponse(
                session.user.toAuthenticatedUserView(), session.expiresAt), HttpStatus.CREATED)
        } catch (error: IllegalArgumentException) {
            response.respondAuthenticationJson(AuthenticationErrorResponse(
                AuthenticationErrorCode.INVALID_REQUEST, error.message), HttpStatus.BAD_REQUEST)
        } catch (error: IllegalStateException) {
            response.respondAuthenticationJson(AuthenticationErrorResponse(
                AuthenticationErrorCode.BOOTSTRAP_REJECTED, error.message), HttpStatus.CONFLICT)
        } finally { password.fill('\u0000') }
    }

    @PostMapping("/auth/login")
    suspend fun login(request: HttpServletRequest, response: HttpServletResponse) {
        if (!request.requireAllowedAuthenticationOrigin(response) ||
            !request.requireSecureAuthenticationTransport(response, settings)) return
        val body = request.receiveAuthenticationRequest<LoginRequest>(response) ?: return
        val retryAfterSeconds = attemptLimiter.retryAfterSeconds(request.remoteAddr, body.username)
        if (retryAfterSeconds != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
            response.respondAuthenticationJson(AuthenticationErrorResponse(
                AuthenticationErrorCode.RATE_LIMITED, "Too many authentication attempts"), HttpStatus.TOO_MANY_REQUESTS)
            return
        }
        val password = body.password.toCharArray()
        try {
            val session = authenticationService.login(body.username, password, body.clientLabel)
            attemptLimiter.recordSuccess(body.username)
            response.setSessionCookie(session.token, session.expiresAt.epochSeconds, settings.secureCookie)
            response.respondAuthenticationJson(AuthenticationSessionResponse(
                session.user.toAuthenticatedUserView(), session.expiresAt))
        } catch (_: AuthenticationRejectedException) {
            attemptLimiter.recordFailure(request.remoteAddr, body.username)
            response.respondAuthenticationJson(AuthenticationErrorResponse(
                AuthenticationErrorCode.INVALID_CREDENTIALS, "Invalid username or password"), HttpStatus.UNAUTHORIZED)
        } catch (error: IllegalArgumentException) {
            attemptLimiter.recordFailure(request.remoteAddr, body.username)
            response.respondAuthenticationJson(AuthenticationErrorResponse(
                AuthenticationErrorCode.INVALID_REQUEST, error.message), HttpStatus.BAD_REQUEST)
        } finally { password.fill('\u0000') }
    }

    @PostMapping("/auth/logout")
    suspend fun logout(request: HttpServletRequest, response: HttpServletResponse) {
        if (!request.requireAllowedAuthenticationOrigin(response) ||
            !request.requireSecureAuthenticationTransport(response, settings)) return
        request.sessionToken()?.let { authenticationService.logout(it) }
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SESSION_COOKIE_NAME, "")
            .maxAge(0).path("/").secure(settings.secureCookie).httpOnly(true).sameSite("Strict").build().toString())
        response.status = HttpStatus.NO_CONTENT.value()
    }
}

internal fun HttpServletRequest.sessionToken(): String? = cookies?.firstOrNull { it.name == SESSION_COOKIE_NAME }?.value

internal suspend fun HttpServletRequest.requireAuthenticated(authentication: AuthenticationService): AuthenticatedRemoteSession {
    val token = sessionToken() ?: throw MissingAuthenticationException()
    return AuthenticatedRemoteSession(token, authentication.authenticate(token) ?: throw MissingAuthenticationException())
}

internal suspend fun HttpServletRequest.authenticateOrNull(authentication: AuthenticationService): AuthenticatedUser? =
    sessionToken()?.let { authentication.authenticate(it) }

internal fun HttpServletResponse.setSessionCookie(token: String, expiresAtEpochSeconds: Long, secureCookie: Boolean) {
    val maxAge = (expiresAtEpochSeconds - Clock.System.now().epochSeconds).coerceIn(0, Int.MAX_VALUE.toLong())
    addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SESSION_COOKIE_NAME, token)
        .maxAge(maxAge).path("/").secure(secureCookie).httpOnly(true).sameSite("Strict").build().toString())
}

internal inline fun <reified T> HttpServletResponse.respondAuthenticationJson(payload: T, status: HttpStatus = HttpStatus.OK) {
    setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
    writeJson(payload, status, authenticationJson)
}

internal fun HttpServletRequest.requireSecureAuthenticationTransport(
    response: HttpServletResponse, settings: ServerHttpSettings,
): Boolean {
    if (isSecureTransport(settings)) return true
    response.respondAuthenticationJson(AuthenticationErrorResponse(
        AuthenticationErrorCode.HTTPS_REQUIRED, "Authentication requires HTTPS"), HttpStatus.UPGRADE_REQUIRED)
    return false
}

internal fun HttpServletRequest.requireAllowedAuthenticationOrigin(response: HttpServletResponse): Boolean {
    if (hasAllowedBrowserOrigin()) return true
    response.respondAuthenticationJson(AuthenticationErrorResponse(
        AuthenticationErrorCode.CROSS_ORIGIN_REJECTED, "Cross-origin browser request rejected"), HttpStatus.FORBIDDEN)
    return false
}

internal suspend inline fun <reified T> HttpServletRequest.receiveAuthenticationRequest(response: HttpServletResponse): T? {
    val body = try {
        readBoundedBody(MAX_AUTHENTICATION_REQUEST_BYTES).decodeToString(throwOnInvalidSequence = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HttpRequestBodyTooLarge) {
        response.respondAuthenticationJson(AuthenticationErrorResponse(
            AuthenticationErrorCode.REQUEST_TOO_LARGE, "Authentication request is too large"), HttpStatus.PAYLOAD_TOO_LARGE)
        return null
    } catch (_: Exception) {
        response.respondAuthenticationJson(AuthenticationErrorResponse(
            AuthenticationErrorCode.REQUEST_READ_FAILED, "Authentication request could not be read"), HttpStatus.BAD_REQUEST)
        return null
    }
    return try { authenticationJson.decodeFromString<T>(body) } catch (_: IllegalArgumentException) {
        response.respondAuthenticationJson(AuthenticationErrorResponse(
            AuthenticationErrorCode.INVALID_REQUEST, "Authentication request is invalid"), HttpStatus.BAD_REQUEST)
        null
    }
}

private const val MAX_AUTHENTICATION_REQUEST_BYTES = 16 * 1024
