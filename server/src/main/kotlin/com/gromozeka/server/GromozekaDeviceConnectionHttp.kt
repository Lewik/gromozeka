package com.gromozeka.server

import com.gromozeka.application.service.AuthenticationRejectedException
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.remote.protocol.AuthenticationErrorResponse
import com.gromozeka.remote.protocol.DeviceConnectionCodeRequest
import com.gromozeka.remote.protocol.DeviceConnectionConsumeRequest
import com.gromozeka.remote.protocol.DeviceConnectionPasswordRequest
import com.gromozeka.remote.protocol.DeviceConnectionStartRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post

internal fun Routing.gromozekaDeviceConnections(
    deviceConnectionService: DeviceConnectionService,
    authenticationService: AuthenticationService,
    attemptLimiter: AuthenticationAttemptLimiter,
    secureCookie: Boolean,
) {
    post("/auth/device-connections") {
        if (!call.requireAllowedAuthenticationOrigin()) return@post
        if (!call.requireSecureAuthenticationTransport()) return@post
        if (!authenticationService.hasUsers()) {
            call.respondAuthenticationJson(
                AuthenticationErrorResponse("Runtime initialization is required"),
                HttpStatusCode.Conflict,
            )
            return@post
        }
        val remoteAddress = call.request.local.remoteAddress
        val retryAfterSeconds = attemptLimiter.deviceConnectionRetryAfterSeconds(remoteAddress)
        if (retryAfterSeconds != null) {
            call.response.headers.append(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
            call.respondAuthenticationJson(
                AuthenticationErrorResponse("Too many device connection requests"),
                HttpStatusCode.TooManyRequests,
            )
            return@post
        }
        val request = call.receiveAuthenticationRequest<DeviceConnectionStartRequest>() ?: return@post
        try {
            val challenge = deviceConnectionService.create(request)
            attemptLimiter.recordDeviceConnectionRequest(remoteAddress)
            call.respondAuthenticationJson(challenge, HttpStatusCode.Created)
        } catch (error: IllegalArgumentException) {
            call.respondDeviceConnectionError(error, HttpStatusCode.BadRequest)
        } catch (error: IllegalStateException) {
            call.respondDeviceConnectionError(error, HttpStatusCode.ServiceUnavailable)
        }
    }

    post("/auth/device-connections/preview") {
        if (!call.requireAllowedAuthenticationOrigin()) return@post
        if (!call.requireSecureAuthenticationTransport()) return@post
        val principal = call.authenticateOrNull(authenticationService)
        if (principal == null) {
            call.respondAuthenticationJson(
                AuthenticationErrorResponse("Authentication required"),
                HttpStatusCode.Unauthorized,
            )
            return@post
        }
        val request = call.receiveAuthenticationRequest<DeviceConnectionCodeRequest>() ?: return@post
        try {
            call.respondAuthenticationJson(deviceConnectionService.preview(request.userCode))
        } catch (error: IllegalArgumentException) {
            call.respondDeviceConnectionError(error, HttpStatusCode.NotFound)
        }
    }

    post("/auth/device-connections/approve") {
        if (!call.requireAllowedAuthenticationOrigin()) return@post
        if (!call.requireSecureAuthenticationTransport()) return@post
        val principal = call.authenticateOrNull(authenticationService)
        if (principal == null) {
            call.respondAuthenticationJson(
                AuthenticationErrorResponse("Authentication required"),
                HttpStatusCode.Unauthorized,
            )
            return@post
        }
        val request = call.receiveAuthenticationRequest<DeviceConnectionCodeRequest>() ?: return@post
        try {
            call.respondAuthenticationJson(
                deviceConnectionService.approve(request.userCode, principal.user.id)
            )
        } catch (error: IllegalArgumentException) {
            call.respondDeviceConnectionError(error, HttpStatusCode.BadRequest)
        } catch (error: IllegalStateException) {
            call.respondDeviceConnectionError(error, HttpStatusCode.Conflict)
        }
    }

    post("/auth/device-connections/deny") {
        if (!call.requireAllowedAuthenticationOrigin()) return@post
        if (!call.requireSecureAuthenticationTransport()) return@post
        val principal = call.authenticateOrNull(authenticationService)
        if (principal == null) {
            call.respondAuthenticationJson(
                AuthenticationErrorResponse("Authentication required"),
                HttpStatusCode.Unauthorized,
            )
            return@post
        }
        val request = call.receiveAuthenticationRequest<DeviceConnectionCodeRequest>() ?: return@post
        try {
            deviceConnectionService.deny(request.userCode, principal.user.id)
            call.respondAuthenticationJson(DeviceConnectionDecisionResponse(denied = true))
        } catch (error: IllegalArgumentException) {
            call.respondDeviceConnectionError(error, HttpStatusCode.BadRequest)
        } catch (error: IllegalStateException) {
            call.respondDeviceConnectionError(error, HttpStatusCode.Conflict)
        }
    }

    post("/auth/device-connections/password") {
        if (!call.requireAllowedAuthenticationOrigin()) return@post
        if (!call.requireSecureAuthenticationTransport()) return@post
        val request = call.receiveAuthenticationRequest<DeviceConnectionPasswordRequest>() ?: return@post
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
            val outcome = deviceConnectionService.approveWithPassword(
                deviceToken = request.deviceToken,
                username = request.username,
                password = password,
            )
            attemptLimiter.recordSuccess(request.username)
            call.respondDeviceConnectionOutcome(outcome, secureCookie)
        } catch (_: AuthenticationRejectedException) {
            attemptLimiter.recordFailure(remoteAddress, request.username)
            call.respondAuthenticationJson(
                AuthenticationErrorResponse("Invalid username or password"),
                HttpStatusCode.Unauthorized,
            )
        } catch (error: IllegalArgumentException) {
            attemptLimiter.recordFailure(remoteAddress, request.username)
            call.respondDeviceConnectionError(error, HttpStatusCode.BadRequest)
        } catch (error: IllegalStateException) {
            call.respondDeviceConnectionError(error, HttpStatusCode.Conflict)
        } finally {
            password.fill('\u0000')
        }
    }

    post("/auth/device-connections/consume") {
        if (!call.requireAllowedAuthenticationOrigin()) return@post
        if (!call.requireSecureAuthenticationTransport()) return@post
        val request = call.receiveAuthenticationRequest<DeviceConnectionConsumeRequest>() ?: return@post
        try {
            call.respondDeviceConnectionOutcome(
                deviceConnectionService.consume(request.deviceToken),
                secureCookie,
            )
        } catch (error: IllegalArgumentException) {
            call.respondDeviceConnectionError(error, HttpStatusCode.BadRequest)
        } catch (error: IllegalStateException) {
            call.respondDeviceConnectionError(error, HttpStatusCode.Conflict)
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondDeviceConnectionOutcome(
    outcome: DeviceConnectionConsumeOutcome,
    secureCookie: Boolean,
) {
    val session = outcome.response.session
    val sessionToken = outcome.sessionToken
    if (session != null) {
        check(sessionToken != null) { "Connected Client response has no session token" }
        setSessionCookie(sessionToken, session.expiresAt.epochSeconds, secureCookie)
    }
    respondAuthenticationJson(outcome.response)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondDeviceConnectionError(
    error: Throwable,
    status: HttpStatusCode,
) {
    respondAuthenticationJson(
        AuthenticationErrorResponse(error.message ?: "Device connection failed"),
        status,
    )
}

@kotlinx.serialization.Serializable
private data class DeviceConnectionDecisionResponse(val denied: Boolean)
