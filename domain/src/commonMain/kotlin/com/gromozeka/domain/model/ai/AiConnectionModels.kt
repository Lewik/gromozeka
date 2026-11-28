package com.gromozeka.domain.model.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Discovery metadata, not model capabilities or a model specification. IDs are kept verbatim. */
@Serializable
data class AiModelCandidate(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
    @SerialName("display_name") val displayName: String? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= 2048 && id.none { it.code < 32 || it.code == 127 }) { "Invalid discovered model id" }
        require(created == null || created >= 0) { "Invalid discovered model timestamp" }
        require(displayName == null || displayName.isNotBlank() && displayName.length <= 2048 &&
            displayName.none { it.code < 32 || it.code == 127 }) { "Invalid discovered model display name" }
    }
}

@Serializable
data class AiConnectionModels(
    val models: List<AiModelCandidate>,
    val observedAt: Instant,
)

fun AiConnection.requireModelListing() {
    require(enabled) { "AI connection is disabled; no model-list request was sent" }
    when (this) {
        is AiConnection.OpenAiSiwc -> require(executionTarget == AiExecutionTarget.Server) {
            "ChatGPT SIWC model listing currently requires Server execution"
        }
        is AiConnection.ConfigurableHttpAiConnection -> require(baseUrl != null) {
            "HTTP model listing requires an explicit base URL; use manual model configuration for other endpoints"
        }
        else -> throw IllegalArgumentException("Model listing is unsupported for connection kind $kind")
    }
}

fun AiConnection.requireGatewayModelListing() {
    requireModelListing()
    require(this is AiConnection.ConfigurableHttpAiConnection) {
        "Worker Gateway model listing requires an SDK HTTP connection"
    }
}
