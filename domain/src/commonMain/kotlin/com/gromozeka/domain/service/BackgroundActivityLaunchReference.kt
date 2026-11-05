package com.gromozeka.domain.service

import kotlinx.serialization.Serializable

/** Correlation with a trusted Server request, not a Worker-provided claim about its actor. */
@Serializable
data class BackgroundActivityLaunchReference(
    val workerRequestId: String,
    val toolCallId: String? = null,
) {
    init {
        require(workerRequestId.isNotBlank()) { "Background activity requires a Worker request id" }
        require(toolCallId == null || toolCallId.isNotBlank()) { "Invalid background activity tool call id" }
    }
}
