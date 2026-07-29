package com.gromozeka.server

import com.gromozeka.application.service.AuthenticationRejectedException
import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.FirstUserBootstrapToken
import com.gromozeka.remote.protocol.AuthenticationErrorResponse
import com.gromozeka.remote.protocol.AuthenticationSessionResponse
import com.gromozeka.remote.protocol.AuthenticationStatusResponse
import com.gromozeka.remote.protocol.BootstrapUserRequest
import com.gromozeka.remote.protocol.LoginRequest
import com.gromozeka.remote.protocol.toAuthenticatedUserView
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val SESSION_COOKIE_NAME = "gromozeka_session"
private val authenticationJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal data class AuthenticatedRemoteSession(
    val token: String,
    val principal: AuthenticatedUser,
)

internal fun Route.gromozekaAuthentication(
    authenticationService: AuthenticationService,
    bootstrapToken: FirstUserBootstrapToken,
    attemptLimiter: AuthenticationAttemptLimiter,
    secureCookie: Boolean,
) {
    get("/auth/status") {
        val principal = call.authenticateOrNull(authenticationService)
        call.respondAuthenticationJson(
            AuthenticationStatusResponse(
                initialized = authenticationService.hasUsers(),
                authenticatedUser = principal?.user?.toAuthenticatedUserView(),
            )
        )
    }

    post("/auth/bootstrap") {
        if (!call.requireSecureAuthenticationTransport()) return@post
        val request = call.receiveAuthenticationRequest<BootstrapUserRequest>() ?: return@post
        val password = request.password.toCharArray()
        try {
            val session = authenticationService.createFirstUser(
                bootstrapToken = request.bootstrapToken,
                username = request.username,
                displayName = request.displayName,
                password = password,
                clientLabel = request.clientLabel,
            )
            call.setSessionCookie(session.token, session.expiresAt.epochSeconds, secureCookie)
            call.respondAuthenticationJson(
                AuthenticationSessionResponse(
                    user = session.user.toAuthenticatedUserView(),
                    expiresAt = session.expiresAt,
                ),
                HttpStatusCode.Created,
            )
        } catch (error: IllegalArgumentException) {
            call.respondAuthenticationJson(
                AuthenticationErrorResponse(error.message ?: "Invalid request"),
                HttpStatusCode.BadRequest,
            )
        } catch (error: IllegalStateException) {
            call.respondAuthenticationJson(
                AuthenticationErrorResponse(error.message ?: "Bootstrap rejected"),
                HttpStatusCode.Conflict,
            )
        } finally {
            password.fill('\u0000')
        }
    }

    post("/auth/login") {
        if (!call.requireSecureAuthenticationTransport()) return@post
        val request = call.receiveAuthenticationRequest<LoginRequest>() ?: return@post
        val remoteAddress = call.request.local.remoteAddress
        val retryAfterSeconds = attemptLimiter.retryAfterSeconds(remoteAddress, request.username)
        if (retryAfterSeconds != null) {
            call.response.headers.append(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
            call.respondAuthenticationJson(
                AuthenticationErrorResponse("Too many authentication attempts"),
                HttpStatusCode.TooManyRequests,
            )
            return@post
        }
        val password = request.password.toCharArray()
        try {
            val session = authenticationService.login(
                username = request.username,
                password = password,
                clientLabel = request.clientLabel,
            )
            attemptLimiter.recordSuccess(request.username)
            call.setSessionCookie(session.token, session.expiresAt.epochSeconds, secureCookie)
            call.respondAuthenticationJson(
                AuthenticationSessionResponse(
                    user = session.user.toAuthenticatedUserView(),
                    expiresAt = session.expiresAt,
                )
            )
        } catch (_: AuthenticationRejectedException) {
            attemptLimiter.recordFailure(remoteAddress, request.username)
            call.respondAuthenticationJson(
                AuthenticationErrorResponse("Invalid username or password"),
                HttpStatusCode.Unauthorized,
            )
        } catch (error: IllegalArgumentException) {
            attemptLimiter.recordFailure(remoteAddress, request.username)
            call.respondAuthenticationJson(
                AuthenticationErrorResponse(error.message ?: "Invalid request"),
                HttpStatusCode.BadRequest,
            )
        } finally {
            password.fill('\u0000')
        }
    }

    post("/auth/logout") {
        call.request.cookies[SESSION_COOKIE_NAME]?.let { authenticationService.logout(it) }
        call.response.cookies.append(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = "",
                maxAge = 0,
                path = "/",
                secure = secureCookie,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
                encoding = CookieEncoding.RAW,
            )
        )
        call.respondText("", status = HttpStatusCode.NoContent)
    }

}

internal suspend fun ApplicationCall.requireAuthenticated(
    authenticationService: AuthenticationService,
): AuthenticatedRemoteSession {
    val token = request.cookies[SESSION_COOKIE_NAME]
        ?: throw MissingAuthenticationException()
    val principal = authenticationService.authenticate(token)
        ?: throw MissingAuthenticationException()
    return AuthenticatedRemoteSession(token, principal)
}

internal suspend fun ApplicationCall.authenticateOrNull(
    authenticationService: AuthenticationService,
): AuthenticatedUser? =
    request.cookies[SESSION_COOKIE_NAME]
        ?.let { authenticationService.authenticate(it) }

private fun ApplicationCall.setSessionCookie(
    token: String,
    expiresAtEpochSeconds: Long,
    secureCookie: Boolean,
) {
    val maxAgeSeconds = (expiresAtEpochSeconds - Clock.System.now().epochSeconds)
        .coerceAtLeast(0)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE_NAME,
            value = token,
            maxAge = maxAgeSeconds,
            path = "/",
            secure = secureCookie,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
            encoding = CookieEncoding.RAW,
        )
    )
}

internal class MissingAuthenticationException : RuntimeException("Authentication required")

private suspend inline fun <reified T> ApplicationCall.respondAuthenticationJson(
    payload: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText(
        authenticationJson.encodeToString(payload),
        ContentType.Application.Json,
        status,
    )
}

private suspend fun ApplicationCall.requireSecureAuthenticationTransport(): Boolean {
    if (isSecureTransport()) return true
    respondAuthenticationJson(
        AuthenticationErrorResponse("Authentication requires HTTPS"),
        HttpStatusCode.UpgradeRequired,
    )
    return false
}

private suspend inline fun <reified T> ApplicationCall.receiveAuthenticationRequest(): T? {
    val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (contentLength != null && contentLength > MAX_AUTHENTICATION_REQUEST_BYTES) {
        respondAuthenticationJson(
            AuthenticationErrorResponse("Authentication request is too large"),
            HttpStatusCode.PayloadTooLarge,
        )
        return null
    }
    val body = runCatching { receiveText() }
        .getOrElse {
            respondAuthenticationJson(
                AuthenticationErrorResponse("Authentication request could not be read"),
                HttpStatusCode.BadRequest,
            )
            return null
        }
    if (body.length > MAX_AUTHENTICATION_REQUEST_BYTES) {
        respondAuthenticationJson(
            AuthenticationErrorResponse("Authentication request is too large"),
            HttpStatusCode.PayloadTooLarge,
        )
        return null
    }
    return runCatching { authenticationJson.decodeFromString<T>(body) }
        .getOrElse {
            respondAuthenticationJson(
                AuthenticationErrorResponse("Authentication request is invalid"),
                HttpStatusCode.BadRequest,
            )
            null
        }
}

private const val MAX_AUTHENTICATION_REQUEST_BYTES = 16 * 1024
