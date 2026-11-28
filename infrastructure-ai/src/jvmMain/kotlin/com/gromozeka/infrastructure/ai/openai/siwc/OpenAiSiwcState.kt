package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.domain.model.ai.AiCallException
import com.gromozeka.domain.model.ai.AiCallFailure
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAiSiwcStoredState(
    val version: Int = 1,
    val clientId: String? = null,
    val status: Status = Status.DISCONNECTED,
    val scopes: List<String> = emptyList(),
    val subject: String? = null,
    val identity: Identity? = null,
    val credentials: Credentials? = null,
    val pendingRefresh: PendingRefresh? = null,
    val savedAt: String? = null,
) {
    @Serializable
    enum class Status { DISCONNECTED, CONNECTED, REAUTH_REQUIRED }

    @Serializable
    data class Identity(val name: String? = null, val email: String? = null)

    @Serializable
    data class Credentials(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMillis: Long,
        val earliestRefreshAtMillis: Long? = null,
    )

    @Serializable
    data class PendingRefresh(
        val credentials: Credentials,
        val scopes: List<String>,
        val idToken: String,
        val receivedAtMillis: Long,
    )
}

internal class OpenAiSiwcException(
    val code: String,
    message: String,
    val retryable: Boolean = false,
    val status: Int? = null,
    cause: Throwable? = null,
    val requestId: String? = null,
) : RuntimeException(message, cause)

internal fun OpenAiSiwcException.toAiCallException(): AiCallException = AiCallException(
    AiCallFailure(
        kind = when {
            code == "deadline_exceeded" -> AiCallFailure.Kind.DEADLINE_EXCEEDED
            code == "sharing_not_enabled" || status == 403 -> AiCallFailure.Kind.ACCESS_DENIED
            code == "invalid_client" -> AiCallFailure.Kind.CONFIGURATION
            status == 401 || code in AUTHENTICATION_ERROR_CODES -> AiCallFailure.Kind.AUTHENTICATION
            status == 429 -> AiCallFailure.Kind.RATE_LIMITED
            code in INVALID_RESPONSE_ERROR_CODES -> AiCallFailure.Kind.INVALID_RESPONSE
            retryable -> AiCallFailure.Kind.TRANSPORT
            status != null -> AiCallFailure.Kind.HTTP_ERROR
            else -> AiCallFailure.Kind.CONFIGURATION
        },
        outcome = AiCallFailure.Outcome.NOT_STARTED,
        attempts = 0,
        httpStatus = status,
    )
)

internal fun interface OpenAiSiwcAccessTokenProvider {
    suspend fun validAccessToken(deadlineNanos: Long): String
}

private val AUTHENTICATION_ERROR_CODES = setOf(
    "sign_in_required",
    "invalid_grant",
    "invalid_refresh_token",
    "token_expired",
    "refresh_token_expired",
    "refresh_token_invalidated",
    "refresh_token_reused",
    "invalid_id_token",
    "account_mismatch",
)

private val INVALID_RESPONSE_ERROR_CODES = setOf(
    "invalid_response",
    "invalid_token_response",
    "invalid_model_catalog",
    "response_too_large",
)
