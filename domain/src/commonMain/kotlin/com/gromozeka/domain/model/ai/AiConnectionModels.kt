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
) {
    init {
        require(id.isNotBlank() && id.length <= 2048 && id.none { it.code < 32 || it.code == 127 }) { "Invalid discovered model id" }
        require(created == null || created >= 0) { "Invalid discovered model timestamp" }
    }
}

@Serializable
data class AiConnectionModels(
    val models: List<AiModelCandidate>,
    val observedAt: Instant,
)

fun AiConnection.requireGatewayModelListing() {
    require(enabled) { "AI connection is disabled; no model-list request was sent" }
    require(this is AiConnection.ConfigurableHttpAiConnection) { "Model listing requires an SDK HTTP connection" }
    require(baseUrl != null) { "Gateway model listing requires an explicit base URL; use manual model configuration for other endpoints" }
}
