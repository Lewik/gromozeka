package com.gromozeka.infrastructure.ai.openai.subscription

internal open class OpenAiSubscriptionApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class OpenAiSubscriptionUnauthorizedException(
    message: String,
) : OpenAiSubscriptionApiException(message)

internal class OpenAiSubscriptionRequestException(
    val statusCode: Int,
    message: String,
) : OpenAiSubscriptionApiException(message)

internal class OpenAiSubscriptionTransportException(
    message: String,
    cause: Throwable? = null,
) : OpenAiSubscriptionApiException(message, cause)
