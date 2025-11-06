package com.gromozeka.bot.services

import com.google.cloud.vertexai.VertexAI
import com.google.cloud.vertexai.api.Candidate
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions
import org.springframework.retry.support.RetryTemplate
import java.util.UUID

/**
 * Workaround for Spring AI Gemini bug where tool call IDs are empty strings.
 *
 * Gemini API doesn't provide IDs for FunctionCall, causing Spring AI to hardcode
 * empty string "" in VertexAiGeminiChatModel.java:610:
 * `return new AssistantMessage.ToolCall("", "function", functionName, functionArguments);`
 *
 * This causes tool execution to fail on iteration 2 with:
 * `com.google.protobuf.InvalidProtocolBufferException: Expect a map object but found: null`
 *
 * This class fixes the issue by generating UUIDs for empty tool call IDs.
 *
 * Related GitHub issues:
 * - https://github.com/spring-projects/spring-ai/issues/4629 (Tool Call chunk merging fails for APIs returning empty string IDs)
 * - https://github.com/spring-projects/spring-ai/issues/843 (VertexAiGeminiChatModel does not support multiple Function Calls)
 *
 * @see VertexAiGeminiChatModel
 */
class VertexAiGeminiChatModelWithIdFix(
    vertexAI: VertexAI,
    defaultOptions: VertexAiGeminiChatOptions,
    toolCallingManager: ToolCallingManager,
    retryTemplate: RetryTemplate,
    observationRegistry: ObservationRegistry
) : VertexAiGeminiChatModel(
    vertexAI,
    defaultOptions,
    toolCallingManager,
    retryTemplate,
    observationRegistry
) {

    override fun responseCandidateToGeneration(candidate: Candidate): List<Generation> {
        val generations = super.responseCandidateToGeneration(candidate)

        // Fix empty tool call IDs by replacing them with UUIDs
        return generations.map { generation ->
            val message = generation.output
            if (message is AssistantMessage && message.toolCalls?.any { it.id().isEmpty() } == true) {
                val fixedToolCalls = message.toolCalls.map { toolCall ->
                    if (toolCall.id().isEmpty()) {
                        AssistantMessage.ToolCall(
                            UUID.randomUUID().toString(),
                            toolCall.type(),
                            toolCall.name(),
                            toolCall.arguments()
                        )
                    } else {
                        toolCall
                    }
                }
                Generation(
                    AssistantMessage.builder()
                        .content(message.text)
                        .properties(message.metadata)
                        .toolCalls(fixedToolCalls)
                        .media(message.media)
                        .build(),
                    generation.metadata
                )
            } else {
                generation
            }
        }
    }
}
