package com.gromozeka.server

import com.gromozeka.application.service.AuthenticationRejectedException
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.remote.protocol.AuthenticationErrorCode
import com.gromozeka.remote.protocol.AuthenticationErrorResponse
import com.gromozeka.remote.protocol.DeviceConnectionCodeRequest
import com.gromozeka.remote.protocol.DeviceConnectionConsumeRequest
import com.gromozeka.remote.protocol.DeviceConnectionPasswordRequest
import com.gromozeka.remote.protocol.DeviceConnectionStartRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth/device-connections")
internal class GromozekaDeviceConnectionHttp(
    private val deviceConnectionService: DeviceConnectionService,
    private val authenticationService: AuthenticationService,
    private val attemptLimiter: AuthenticationAttemptLimiter,
    private val settings: ServerHttpSettings,
) {
    private fun accepts(request: HttpServletRequest, response: HttpServletResponse): Boolean =
        request.requireAllowedAuthenticationOrigin(response) && request.requireSecureAuthenticationTransport(response, settings)

    @PostMapping
    suspend fun start(request: HttpServletRequest, response: HttpServletResponse) {
        if (!accepts(request, response)) return
        if (!authenticationService.hasUsers()) {
            response.respondAuthenticationJson(AuthenticationErrorResponse(
                AuthenticationErrorCode.RUNTIME_NOT_INITIALIZED, "Runtime initialization is required"), HttpStatus.CONFLICT)
            return
        }
        val retry = attemptLimiter.deviceConnectionRetryAfterSeconds(request.remoteAddr)
        if (retry != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, retry.toString())
            response.respondAuthenticationJson(AuthenticationErrorResponse(AuthenticationErrorCode.DEVICE_CONNECTION_RATE_LIMITED,
                "Too many device connection requests"), HttpStatus.TOO_MANY_REQUESTS)
            return
        }
        val body = request.receiveAuthenticationRequest<DeviceConnectionStartRequest>(response) ?: return
        try {
            val challenge = deviceConnectionService.create(body)
            attemptLimiter.recordDeviceConnectionRequest(request.remoteAddr)
            response.respondAuthenticationJson(challenge, HttpStatus.CREATED)
        } catch (error: IllegalArgumentException) {
            response.respondDeviceConnectionError(error, HttpStatus.BAD_REQUEST)
        } catch (error: IllegalStateException) {
            response.respondDeviceConnectionError(error, HttpStatus.SERVICE_UNAVAILABLE)
        }
    }

    @PostMapping("/preview")
    suspend fun preview(request: HttpServletRequest, response: HttpServletResponse) {
        if (!accepts(request, response)) return
        if (request.authenticateOrNull(authenticationService) == null) {
            response.authenticationRequired()
            return
        }
        val body = request.receiveAuthenticationRequest<DeviceConnectionCodeRequest>(response) ?: return
        try { response.respondAuthenticationJson(deviceConnectionService.preview(body.userCode)) }
        catch (error: IllegalArgumentException) { response.respondDeviceConnectionError(error, HttpStatus.NOT_FOUND) }
    }

    @PostMapping("/approve")
    suspend fun approve(request: HttpServletRequest, response: HttpServletResponse) {
        if (!accepts(request, response)) return
        val principal = request.authenticateOrNull(authenticationService)
        if (principal == null) { response.authenticationRequired(); return }
        val body = request.receiveAuthenticationRequest<DeviceConnectionCodeRequest>(response) ?: return
        try { response.respondAuthenticationJson(deviceConnectionService.approve(body.userCode, principal.user.id)) }
        catch (error: IllegalArgumentException) { response.respondDeviceConnectionError(error, HttpStatus.BAD_REQUEST) }
        catch (error: IllegalStateException) { response.respondDeviceConnectionError(error, HttpStatus.CONFLICT) }
    }

    @PostMapping("/deny")
    suspend fun deny(request: HttpServletRequest, response: HttpServletResponse) {
        if (!accepts(request, response)) return
        val principal = request.authenticateOrNull(authenticationService)
        if (principal == null) { response.authenticationRequired(); return }
        val body = request.receiveAuthenticationRequest<DeviceConnectionCodeRequest>(response) ?: return
        try {
            deviceConnectionService.deny(body.userCode, principal.user.id)
            response.respondAuthenticationJson(DeviceConnectionDecisionResponse(denied = true))
        } catch (error: IllegalArgumentException) { response.respondDeviceConnectionError(error, HttpStatus.BAD_REQUEST) }
        catch (error: IllegalStateException) { response.respondDeviceConnectionError(error, HttpStatus.CONFLICT) }
    }

    @PostMapping("/password")
    suspend fun password(request: HttpServletRequest, response: HttpServletResponse) {
        if (!accepts(request, response)) return
        val body = request.receiveAuthenticationRequest<DeviceConnectionPasswordRequest>(response) ?: return
        val retry = attemptLimiter.retryAfterSeconds(request.remoteAddr, body.username)
        if (retry != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, retry.toString())
            response.respondAuthenticationJson(AuthenticationErrorResponse(
                AuthenticationErrorCode.RATE_LIMITED, "Too many authentication attempts"), HttpStatus.TOO_MANY_REQUESTS)
            return
        }
        val password = body.password.toCharArray()
        try {
            val outcome = deviceConnectionService.approveWithPassword(body.deviceToken, body.username, password)
            attemptLimiter.recordSuccess(body.username)
            response.respondDeviceConnectionOutcome(outcome, settings.secureCookie)
        } catch (_: AuthenticationRejectedException) {
            attemptLimiter.recordFailure(request.remoteAddr, body.username)
            response.respondAuthenticationJson(AuthenticationErrorResponse(
                AuthenticationErrorCode.INVALID_CREDENTIALS, "Invalid username or password"), HttpStatus.UNAUTHORIZED)
        } catch (error: IllegalArgumentException) {
            attemptLimiter.recordFailure(request.remoteAddr, body.username)
            response.respondDeviceConnectionError(error, HttpStatus.BAD_REQUEST)
        } catch (error: IllegalStateException) { response.respondDeviceConnectionError(error, HttpStatus.CONFLICT) }
        finally { password.fill('\u0000') }
    }

    @PostMapping("/consume")
    suspend fun consume(request: HttpServletRequest, response: HttpServletResponse) {
        if (!accepts(request, response)) return
        val body = request.receiveAuthenticationRequest<DeviceConnectionConsumeRequest>(response) ?: return
        try { response.respondDeviceConnectionOutcome(deviceConnectionService.consume(body.deviceToken), settings.secureCookie) }
        catch (error: IllegalArgumentException) { response.respondDeviceConnectionError(error, HttpStatus.BAD_REQUEST) }
        catch (error: IllegalStateException) { response.respondDeviceConnectionError(error, HttpStatus.CONFLICT) }
    }
}

private fun HttpServletResponse.authenticationRequired() = respondAuthenticationJson(
    AuthenticationErrorResponse(AuthenticationErrorCode.AUTHENTICATION_REQUIRED, "Authentication required"), HttpStatus.UNAUTHORIZED)

private fun HttpServletResponse.respondDeviceConnectionOutcome(outcome: DeviceConnectionConsumeOutcome, secureCookie: Boolean) {
    outcome.response.session?.let { session ->
        setSessionCookie(checkNotNull(outcome.sessionToken) { "Connected Client response has no session token" },
            session.expiresAt.epochSeconds, secureCookie)
    }
    respondAuthenticationJson(outcome.response)
}

private fun HttpServletResponse.respondDeviceConnectionError(error: Throwable, status: HttpStatus) =
    respondAuthenticationJson(AuthenticationErrorResponse(
        code = if (error is InvalidDeviceConnectionException || error is ExpiredDeviceConnectionException)
            AuthenticationErrorCode.INVALID_DEVICE_CONNECTION else AuthenticationErrorCode.DEVICE_CONNECTION_FAILED,
        message = error.message,
    ), status)

@kotlinx.serialization.Serializable
private data class DeviceConnectionDecisionResponse(val denied: Boolean)
