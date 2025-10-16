/*
 * Copyright 2023-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.claudecode.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.claudecode.api.ClaudeCodeStreamResponse;
import org.springframework.ai.claudecode.api.ContentBlock;
import org.springframework.ai.claudecode.api.Usage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Claude Code CLI responses to Spring AI format.
 */
public class ClaudeCodeToSpringAiConverter {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Convert Claude Code stream response to Spring AI ChatResponse.
     */
    public static ChatResponse convertToChatResponse(
            ClaudeCodeStreamResponse.AssistantMessage assistantMessage,
            Usage accumulatedUsage) {

        List<Generation> generations = new ArrayList<>();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();

        for (ContentBlock content : assistantMessage.message().content()) {
            switch (content) {
                case ContentBlock.Text text -> {
                    generations.add(new Generation(
                        new AssistantMessage(text.text(), Map.of()),
                        ChatGenerationMetadata.builder()
                            .finishReason(assistantMessage.message().stopReason())
                            .build()
                    ));
                }
                case ContentBlock.TextDelta textDelta -> {
                    generations.add(new Generation(
                        new AssistantMessage(textDelta.text(), Map.of()),
                        ChatGenerationMetadata.builder()
                            .finishReason(assistantMessage.message().stopReason())
                            .build()
                    ));
                }
                case ContentBlock.Thinking thinking -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("thinking", true);
                    metadata.put("signature", thinking.signature());
                    generations.add(new Generation(
                        new AssistantMessage(thinking.thinking(), metadata),
                        ChatGenerationMetadata.builder()
                            .finishReason(assistantMessage.message().stopReason())
                            .build()
                    ));
                }
                case ContentBlock.ThinkingDelta thinkingDelta -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("thinking", true);
                    generations.add(new Generation(
                        new AssistantMessage(thinkingDelta.thinking(), metadata),
                        ChatGenerationMetadata.builder()
                            .finishReason(assistantMessage.message().stopReason())
                            .build()
                    ));
                }
                case ContentBlock.RedactedThinking redacted -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("redacted_thinking", true);
                    metadata.put("data", redacted.data());
                    generations.add(new Generation(
                        new AssistantMessage(null, metadata),
                        ChatGenerationMetadata.builder()
                            .finishReason(assistantMessage.message().stopReason())
                            .build()
                    ));
                }
                case ContentBlock.ToolUse toolUse -> {
                    String argumentsJson = toJson(toolUse.input());
                    toolCalls.add(new AssistantMessage.ToolCall(
                        toolUse.id(),
                        "function",
                        toolUse.name(),
                        argumentsJson
                    ));
                }
                default -> {
                    // Skip unknown content types
                }
            }
        }

        // If there are tool calls, add them as a separate generation
        if (!toolCalls.isEmpty()) {
            generations.add(new Generation(
                new AssistantMessage("", Map.of(), toolCalls),
                ChatGenerationMetadata.builder()
                    .finishReason(assistantMessage.message().stopReason())
                    .build()
            ));
        }

        // If no generations were created but we have a stop reason, add empty generation
        if (generations.isEmpty() && assistantMessage.message().stopReason() != null) {
            generations.add(new Generation(
                new AssistantMessage(null, Map.of()),
                ChatGenerationMetadata.builder()
                    .finishReason(assistantMessage.message().stopReason())
                    .build()
            ));
        }

        ChatResponseMetadata metadata = buildMetadata(assistantMessage, accumulatedUsage);

        return new ChatResponse(generations, metadata);
    }

    private static ChatResponseMetadata buildMetadata(
            ClaudeCodeStreamResponse.AssistantMessage assistantMessage,
            Usage accumulatedUsage) {

        Usage messageUsage = assistantMessage.message().usage();
        Usage effectiveUsage = messageUsage != null ? messageUsage : accumulatedUsage;

        DefaultUsage usage = null;
        if (effectiveUsage != null) {
            usage = new DefaultUsage(
                effectiveUsage.inputTokens(),
                effectiveUsage.outputTokens(),
                effectiveUsage.inputTokens() + effectiveUsage.outputTokens(),
                effectiveUsage
            );
        }

        return ChatResponseMetadata.builder()
            .id(assistantMessage.message().id())
            .model(assistantMessage.message().model())
            .usage(usage)
            .keyValue("stop-reason", assistantMessage.message().stopReason())
            .keyValue("stop-sequence", assistantMessage.message().stopSequence())
            .build();
    }

    private static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert to JSON", e);
        }
    }
}
