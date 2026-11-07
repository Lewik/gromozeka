package com.gromozeka.infrastructure.ai.runtime

import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicServiceException
import com.openai.errors.OpenAIException
import com.openai.errors.OpenAIServiceException

/** SDK exceptions can contain response bodies and private gateway headers; never retain them as causes. */
internal class AiHttpRequestException(val statusCode: Int?, message: String) : RuntimeException(message)

internal fun OpenAIException.safeHttpFailure(): AiHttpRequestException =
    safeHttpFailure((this as? OpenAIServiceException)?.statusCode(), this)

internal fun AnthropicException.safeHttpFailure(): AiHttpRequestException =
    safeHttpFailure((this as? AnthropicServiceException)?.statusCode(), this)

private fun safeHttpFailure(status: Int?, error: Throwable): AiHttpRequestException = AiHttpRequestException(status,
    when (status) {
        401 -> "AI authentication was rejected (HTTP 401). Check the selected token; this does not by itself prove token expiry."
        403 -> "AI endpoint denied access (HTTP 403). Check permissions for the configured route."
        null -> when {
            generateSequence(error) { it.cause }.any { it is javax.net.ssl.SSLException } ->
                "AI TLS connection failed. Check the endpoint certificate, hostname and configured CA bundle."
            generateSequence(error) { it.cause }.any { it is java.net.SocketTimeoutException } ->
                "AI HTTP request timed out."
            else -> "AI SDK request failed. Provider response details were withheld to protect credentials and private gateway metadata."
        }
        else -> "AI endpoint request failed (HTTP $status). Provider response details were withheld to protect credentials and private gateway metadata."
    }
)
