package org.springframework.ai.google.genai

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.genai.Client
import com.google.genai.types.Candidate
import io.micrometer.observation.ObservationRegistry
import klog.KLoggers
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.retry.support.RetryTemplate
import java.util.UUID

/**
 * Workaround extension for GoogleGenAiChatModel addressing two issues in Spring AI snapshot.
 *
 * ## Issue 1: Empty Tool Call IDs
 *
 * **Problem:**
 * Gemini API doesn't provide IDs for FunctionCall. Spring AI hardcodes empty string ""
 * in GoogleGenAiChatModel.java:~620:
 * ```java
 * return new AssistantMessage.ToolCall("", "function", functionName, functionArguments);
 * ```
 *
 * This causes tool execution to fail on iteration 2+ with:
 * ```
 * com.google.protobuf.InvalidProtocolBufferException: Expect a map object but found: null
 * ```
 *
 * **Solution:** Override responseCandidateToGeneration() to inject UUIDs for empty IDs.
 *
 * **Related Issues:**
 * - https://github.com/spring-projects/spring-ai/issues/4629 (closed, but not fixed for GoogleGenAiChatModel)
 * - https://github.com/spring-projects/spring-ai/issues/843 (multiple function calls support)
 *
 * ---
 *
 * ## Issue 2: Missing includeThoughts Parameter
 *
 * **Problem:**
 * Spring AI snapshot 1.1.0-785 implements thinkingBudget but NOT includeThoughts.
 * GoogleGenAiChatModel.java:~715 creates ThinkingConfig without includeThoughts:
 * ```java
 * ThinkingConfig.builder().thinkingBudget(...).build()
 * ```
 *
 * Without includeThoughts(true), Gemini doesn't return thought summaries in responses,
 * only usage metrics (thoughtsTokenCount).
 *
 * **Solution:** Override createGeminiRequest() to modify ThinkingConfig via toBuilder() pattern.
 *
 * **Requirements:**
 * - Thought summaries ONLY available when function calling is enabled
 * - Without tools in request, includeThoughts has no effect
 * - See: https://ai.google.dev/gemini-api/docs/thinking-mode#thought-summaries
 *
 * **Related:**
 * - https://github.com/spring-projects/spring-ai/pull/3914 (thinking support PR, merged Aug 2025)
 * - https://googleapis.github.io/java-genai/javadoc/com/google/genai/types/ThinkingConfig.html
 * - https://ai.google.dev/gemini-api/docs/thinking-mode
 *
 * ---
 *
 * ## Removal Plan
 *
 * This workaround can be removed when:
 * 1. Spring AI properly generates non-empty tool call IDs for Gemini
 * 2. Spring AI exposes includeThoughts parameter in GoogleGenAiChatOptions
 *
 * Track progress at: https://github.com/spring-projects/spring-ai/issues
 *
 * @see GoogleGenAiChatModel
 * @see com.google.genai.types.ThinkingConfig
 */
class GoogleGenAiGeminiChatModelWithWorkarounds(
    client: Client,
    defaultOptions: GoogleGenAiChatOptions,
    toolCallingManager: ToolCallingManager,
    retryTemplate: RetryTemplate,
    observationRegistry: ObservationRegistry
) : GoogleGenAiChatModel(
    client,
    defaultOptions,
    toolCallingManager,
    retryTemplate,
    observationRegistry
) {
    private val log = KLoggers.logger(this)

    companion object {
        private val objectMapper = ObjectMapper()
    }

    /**
     * Fix 1: Override to extract thought summaries and fix empty tool call IDs.
     *
     * This method addresses two Spring AI limitations:
     * 1. Spring AI ignores thought parts from Gemini API
     * 2. Spring AI creates empty tool call IDs causing protobuf errors
     *
     * Solution: Process candidate.content().parts() manually to:
     * - Extract thought summaries as separate AssistantMessages with metadata["thinking"]=true
     * - Fix empty tool call IDs by injecting UUIDs
     * - Preserve normal text parts as-is
     */
    override fun responseCandidateToGeneration(candidate: Candidate): List<Generation> {
        val candidateIndex = candidate.index().orElse(0)
        val finishReason = candidate.finishReason().orElse(
            com.google.genai.types.FinishReason(com.google.genai.types.FinishReason.Known.STOP)
        )

        val baseMetadata = mapOf(
            "candidateIndex" to candidateIndex,
            "finishReason" to finishReason
        )

        val chatGenerationMetadata = ChatGenerationMetadata.builder()
            .finishReason(finishReason.toString())
            .build()

        // Get parts from candidate
        val parts = candidate.content()
            .flatMap { it.parts() }
            .orElse(emptyList())

        if (parts.isEmpty()) {
            log.debug { "Empty candidate parts" }
            return emptyList()
        }

        log.debug { "Processing ${parts.size} parts from candidate" }

        val generations = mutableListOf<Generation>()

        parts.forEachIndexed { idx, part ->
            when {
                // 1. Thought summary part
                part.thought().isPresent -> {
                    val thinkingText = part.text().orElse("")
                    log.debug { "Thought part[$idx]: '${thinkingText.take(100)}'" }

                    val thinkingMetadata = baseMetadata + mapOf(
                        "thinking" to true,
                        "signature" to "gemini-thinking-$candidateIndex-$idx"
                    )

                    generations.add(
                        Generation(
                            AssistantMessage.builder()
                                .content(thinkingText)
                                .properties(thinkingMetadata)
                                .build(),
                            chatGenerationMetadata
                        )
                    )
                }

                // 2. Function call part
                part.functionCall().isPresent -> {
                    val functionCall = part.functionCall().get()
                    val functionName = functionCall.name().orElse("")
                    val functionArgs = mapToJson(functionCall.args().orElse(emptyMap()))

                    log.debug { "Function call part[$idx]: $functionName" }

                    // Fix empty ID by injecting UUID
                    val toolCall = AssistantMessage.ToolCall(
                        UUID.randomUUID().toString(),
                        "function",
                        functionName,
                        functionArgs
                    )

                    generations.add(
                        Generation(
                            AssistantMessage.builder()
                                .content("")
                                .properties(baseMetadata)
                                .toolCalls(listOf(toolCall))
                                .build(),
                            chatGenerationMetadata
                        )
                    )
                }

                // 3. Regular text part
                part.text().isPresent -> {
                    val text = part.text().get()

                    generations.add(
                        Generation(
                            AssistantMessage.builder()
                                .content(text)
                                .properties(baseMetadata)
                                .build(),
                            chatGenerationMetadata
                        )
                    )
                }

                else -> {
                    log.debug { "Unknown part[$idx] type, skipping" }
                }
            }
        }

        log.debug { "Created ${generations.size} generations from ${parts.size} parts" }
        return generations
    }

    /**
     * Convert Map to JSON string using Jackson ObjectMapper.
     *
     * Uses Jackson for proper JSON escaping (quotes, backslashes, special chars).
     * This matches the parent class implementation but is accessible from Kotlin.
     */
    private fun mapToJson(map: Map<String, Any>): String {
        return try {
            objectMapper.writeValueAsString(map)
        } catch (e: Exception) {
            throw RuntimeException("Failed to convert map to JSON", e)
        }
    }

    /**
     * Fix 2: Override to force includeThoughts(true) when thinking is enabled.
     *
     * Spring AI doesn't expose includeThoughts parameter yet, but the underlying
     * Google Gen AI SDK supports it. This method modifies the ThinkingConfig
     * after parent creates the request to enable thought summaries.
     *
     * Note: Thought summaries are only returned when function calling is active.
     * Without tools in the request, this parameter has no effect.
     */
    override fun createGeminiRequest(prompt: Prompt): GeminiRequest {
        val original = super.createGeminiRequest(prompt)

        // Modify thinkingConfig to include thoughts if thinking is enabled
        val thinkingConfigOpt = original.config().thinkingConfig()
        if (thinkingConfigOpt.isPresent) {
            val originalThinking = thinkingConfigOpt.get()

            // Force includeThoughts(true) for thinking mode
            val modifiedThinking = originalThinking.toBuilder()
                .includeThoughts(true)
                .build()

            // Rebuild config with modified thinkingConfig
            val modifiedConfig = original.config().toBuilder()
                .thinkingConfig(modifiedThinking)
                .build()

            // Return new GeminiRequest with modified config
            return GeminiRequest(
                original.contents(),
                original.modelName(),
                modifiedConfig
            )
        }

        return original
    }
}
