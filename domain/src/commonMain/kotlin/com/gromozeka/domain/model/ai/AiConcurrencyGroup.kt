package com.gromozeka.domain.model.ai

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
data class AiConcurrencyGroup(
    val id: Id,
    val displayName: String,
    val maxConcurrentCalls: Int? = 4,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 255) { "AI concurrency group name must contain 1 to 255 characters" }
        require(maxConcurrentCalls == null || maxConcurrentCalls in 1..64) {
            "AI concurrency group limit must be between 1 and 64, or null for unlimited"
        }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
                "Invalid AI concurrency group id"
            }
        }
    }

    companion object {
        val DEFAULT_ID = Id("default")
        val DEFAULT = AiConcurrencyGroup(DEFAULT_ID, "Default")
    }
}
