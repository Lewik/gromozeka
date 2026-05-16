package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AiProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Optional static knowledge about a provider model.
 *
 * Model specs are not the source of truth for whether a model may be called.
 * [AiModelConfiguration] decides that. A missing spec means Gromozeka does not
 * know safe context-window or auto-compaction values for this provider model
 * and callers must disable spec-dependent behavior instead of guessing.
 */
@Serializable
data class AiModelSpec(
    val id: String,
    val provider: AiProvider,
    val contextWindowTokens: Int,
    val maxOutputTokens: Int? = null,
    val reasoning: AiReasoningCapabilities? = null,
    val autoCompaction: AutoCompaction? = null,
) {
    init {
        require(id.isNotBlank()) { "AI model id must not be blank" }
        require(contextWindowTokens > 0) { "AI model context window must be positive" }
        require(maxOutputTokens == null || maxOutputTokens in 1..contextWindowTokens) {
            "AI model max output tokens must be inside context window"
        }
        val threshold = autoCompaction?.thresholdTokens(contextWindowTokens)
        require(threshold == null || threshold in 1..contextWindowTokens) {
            "AI model auto compaction threshold must be inside context window"
        }
    }

    val autoCompactionThresholdTokens: Int?
        get() = autoCompaction?.thresholdTokens(contextWindowTokens)

    /**
     * Auto-compaction threshold policy for models that support provider-side compaction.
     */
    @Serializable
    @JsonClassDiscriminator("type")
    sealed interface AutoCompaction {
        fun thresholdTokens(contextWindowTokens: Int): Int

        @Serializable
        @SerialName("percent")
        data class Percent(val value: Int) : AutoCompaction {
            init {
                require(value in 1..100) { "AI model auto compaction percent must be in 1..100" }
            }

            override fun thresholdTokens(contextWindowTokens: Int): Int =
                (contextWindowTokens.toLong() * value / 100).toInt()
        }

        @Serializable
        @SerialName("absolute")
        data class Absolute(val tokens: Int) : AutoCompaction {
            init {
                require(tokens > 0) { "AI model auto compaction threshold must be positive" }
            }

            override fun thresholdTokens(contextWindowTokens: Int): Int = tokens
        }
    }
}
