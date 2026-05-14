package com.gromozeka.infrastructure.ai.openai.subscription

import java.security.MessageDigest

internal const val OPENAI_SUBSCRIPTION_KEY_MAX_LENGTH = 64

internal fun String.toOpenAiSubscriptionKey(): String {
    if (length <= OPENAI_SUBSCRIPTION_KEY_MAX_LENGTH) return this

    val hash = MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    return "gzk:${hash.take(OPENAI_SUBSCRIPTION_KEY_MAX_LENGTH - "gzk:".length)}"
}
