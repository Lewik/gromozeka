package com.gromozeka.domain.model.ai

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The same deadline travels to the executor; delivery, queueing and retry delays do not reset it. */
object AiHttpCallBudget {
    const val DEFAULT_TIMEOUT_SECONDS = 600
    const val MAX_TIMEOUT_SECONDS = 86_400

    fun deadline(timeoutSeconds: Int?, requestedDeadline: Instant?, now: Instant): Instant {
        val seconds = timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
        require(seconds in 1..MAX_TIMEOUT_SECONDS) { "AI HTTP timeout must be between 1 and 86400 seconds" }
        val configured = now + seconds.seconds
        return requestedDeadline?.let { minOf(it, configured) } ?: configured
    }
}
