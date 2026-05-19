package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AiProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Static knowledge about a provider model.
 *
 * A model spec describes what the model itself can do and which limits are safe
 * to assume. User-facing [AiModelConfiguration] values only decide how a known
 * provider model is connected and tuned.
 */
@Serializable
data class AiModelSpec(
    val id: String,
    val provider: AiProvider,
    val capabilities: Set<AiModelCapability>,
    val limits: Limits = Limits(),
    val reasoning: AiReasoningCapabilities? = null,
) {
    init {
        require(id.isNotBlank()) { "AI model id must not be blank" }
        require(capabilities.isNotEmpty()) { "AI model capabilities must not be empty" }
        require((AiModelCapability.TEXT_GENERATION in capabilities) == (limits.textGeneration != null)) {
            "AI text generation capability requires text generation limits and vice versa"
        }
        require((AiModelCapability.EMBEDDINGS in capabilities) == (limits.embeddings != null)) {
            "AI embeddings capability requires embedding limits and vice versa"
        }
        require(reasoning == null || AiModelCapability.TEXT_GENERATION in capabilities) {
            "AI reasoning capabilities are valid only for text generation models"
        }
    }

    val contextWindowTokens: Int?
        get() = limits.textGeneration?.contextWindowTokens

    val maxOutputTokens: Int?
        get() = limits.textGeneration?.maxOutputTokens

    val autoCompactionThresholdTokens: Int?
        get() = limits.textGeneration?.let { textGeneration ->
            textGeneration.autoCompaction?.thresholdTokens(textGeneration.contextWindowTokens)
        }

    @Serializable
    data class Limits(
        val textGeneration: TextGeneration? = null,
        val embeddings: Embeddings? = null,
    ) {
        @Serializable
        data class TextGeneration(
            val contextWindowTokens: Int,
            val maxOutputTokens: Int? = null,
            val autoCompaction: AutoCompaction? = null,
        ) {
            init {
                require(contextWindowTokens > 0) { "AI model context window must be positive" }
                require(maxOutputTokens == null || maxOutputTokens in 1..contextWindowTokens) {
                    "AI model max output tokens must be inside context window"
                }
                val threshold = autoCompaction?.thresholdTokens(contextWindowTokens)
                require(threshold == null || threshold in 1..contextWindowTokens) {
                    "AI model auto compaction threshold must be inside context window"
                }
            }
        }

        @Serializable
        data class Embeddings(
            val dimensions: Int? = null,
            val maxInputTokens: Int? = null,
        ) {
            init {
                require(dimensions == null || dimensions > 0) { "AI embedding dimensions must be positive" }
                require(maxInputTokens == null || maxInputTokens > 0) { "AI embedding max input tokens must be positive" }
            }
        }
    }

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

@Serializable
enum class AiModelCapability {
    TEXT_GENERATION,
    STRUCTURED_OUTPUT,
    TOOL_CALLING,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    EMBEDDINGS,
}
