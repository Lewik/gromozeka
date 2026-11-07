package com.gromozeka.domain.model.ai

import kotlinx.serialization.Serializable

/** A terminal logical-call result. Its retry budget is already spent or further attempts are unsafe. */
@Serializable
data class AiCallFailure(
    val kind: Kind,
    val outcome: Outcome,
    val attempts: Int?,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
    val workerRequestId: String? = null,
) {
    init {
        require(attempts == null || attempts >= 0) { "AI attempt count must not be negative" }
        require(httpStatus == null || httpStatus in 100..599) { "Invalid AI HTTP status" }
        require(retryAfterMillis == null || retryAfterMillis >= 0) { "Invalid AI retry delay" }
        require(workerRequestId == null || workerRequestId.matches(Regex("[A-Za-z0-9-]{1,128}"))) {
            "Invalid AI Worker request id"
        }
    }

    @Serializable
    enum class Kind { AUTHENTICATION, ACCESS_DENIED, RATE_LIMITED, HTTP_ERROR, TRANSPORT, TLS, INVALID_RESPONSE, DEADLINE_EXCEEDED, WORKER_FAILURE, CONFIGURATION }

    @Serializable
    enum class Outcome { NOT_STARTED, REJECTED, UNKNOWN }
}

/** Deliberately has no SDK cause, response body or headers, including suppressed exceptions. */
class AiCallException(val failure: AiCallFailure) : RuntimeException(buildString {
    append(when (failure.kind) {
        AiCallFailure.Kind.AUTHENTICATION -> "AI authentication was rejected. Check the selected token; this does not by itself prove token expiry."
        AiCallFailure.Kind.ACCESS_DENIED -> "AI endpoint denied access. Check permissions for the configured route."
        AiCallFailure.Kind.RATE_LIMITED -> "AI endpoint rejected the call because of a rate limit."
        AiCallFailure.Kind.HTTP_ERROR -> "AI endpoint returned an HTTP error."
        AiCallFailure.Kind.TRANSPORT -> "AI transport failed."
        AiCallFailure.Kind.TLS -> "AI TLS connection failed. Check the endpoint certificate, hostname and configured CA bundle."
        AiCallFailure.Kind.INVALID_RESPONSE -> "AI endpoint returned an invalid or incomplete response."
        AiCallFailure.Kind.DEADLINE_EXCEEDED -> "AI logical-call deadline expired."
        AiCallFailure.Kind.WORKER_FAILURE -> "AI Worker execution did not return a usable result."
        AiCallFailure.Kind.CONFIGURATION -> "AI HTTP configuration or the selected credential source is unavailable or invalid."
    })
    failure.httpStatus?.let { append(" HTTP $it.") }
    append(" Attempts: ${failure.attempts ?: "unknown"}. Outcome: ${failure.outcome}. Do not automatically repeat this call.")
    failure.workerRequestId?.let { append(" Worker request: $it.") }
})
