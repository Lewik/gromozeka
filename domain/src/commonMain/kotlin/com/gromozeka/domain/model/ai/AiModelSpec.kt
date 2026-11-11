package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AiProvider
import kotlinx.serialization.Serializable

/**
 * Static knowledge about a provider model.
 *
 * A model spec describes the capabilities, safe limits and explicit request rules
 * of a provider model route. Copy and adjust it for an exact gateway model ID;
 * model names do not select request behavior. [AiModelConfiguration] decides how
 * that route is connected and tuned.
 */
@Serializable
data class AiModelSpec(
    val id: String,
    val provider: AiProvider,
    val capabilities: Set<AiModelCapability>,
    val limits: Limits = Limits(),
    val reasoning: AiReasoningCapabilities? = null,
    val behavior: AiModelBehavior = AiModelBehavior(),
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
        require(behavior == AiModelBehavior() || AiModelCapability.TEXT_GENERATION in capabilities) {
            "AI model request behavior is valid only for text generation models"
        }
        require(behavior.defaultReasoningMode == null || reasoning?.modes.isNullOrEmpty() ||
            behavior.defaultReasoningMode in reasoning.modes) {
            "Default reasoning mode must be supported by the model spec"
        }
        require(behavior.minimumToolReasoningEffort == null || reasoning?.efforts.isNullOrEmpty() ||
            reasoning.efforts.any { it >= behavior.minimumToolReasoningEffort }) {
            "Minimum tool reasoning effort must be reachable by the model spec"
        }
        require(
            capabilities.intersect(AiModelCapability.INPUT_CAPABILITIES).isEmpty() ||
                AiModelCapability.TEXT_GENERATION in capabilities
        ) {
            "AI model input capabilities are valid only for text generation models"
        }
    }

    val contextWindowTokens: Int?
        get() = limits.textGeneration?.contextWindowTokens

    val maxOutputTokens: Int?
        get() = limits.textGeneration?.maxOutputTokens

    @Serializable
    data class Limits(
        val textGeneration: TextGeneration? = null,
        val embeddings: Embeddings? = null,
    ) {
        @Serializable
        data class TextGeneration(
            val contextWindowTokens: Int,
            val maxOutputTokens: Int? = null,
        ) {
            init {
                require(contextWindowTokens > 0) { "AI model context window must be positive" }
                require(maxOutputTokens == null || maxOutputTokens in 1..contextWindowTokens) {
                    "AI model max output tokens must be inside context window"
                }

            }
        }

        @Serializable
        data class Embeddings(
            /**
             * Output dimensions returned when the request does not override them.
             */
            val dimensions: Int? = null,
            val maxInputTokens: Int? = null,
        ) {
            init {
                require(dimensions == null || dimensions > 0) { "AI embedding dimensions must be positive" }
                require(maxInputTokens == null || maxInputTokens > 0) { "AI embedding max input tokens must be positive" }
            }
        }
    }


}

@Serializable
enum class AiModelCapability {
    TEXT_GENERATION,
    IMAGE_INPUT,
    DOCUMENT_INPUT,
    STRUCTURED_OUTPUT,
    TOOL_CALLING,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    EMBEDDINGS,

    ;

    companion object {
        val INPUT_CAPABILITIES = setOf(IMAGE_INPUT, DOCUMENT_INPUT)
    }
}
