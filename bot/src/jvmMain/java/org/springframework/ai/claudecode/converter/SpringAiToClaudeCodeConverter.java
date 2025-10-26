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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.claudecode.api.ClaudeMessage;
import org.springframework.ai.claudecode.api.ContentBlock;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts Spring AI messages to Claude Code CLI format.
 */
public class SpringAiToClaudeCodeConverter {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiToClaudeCodeConverter.class);

    /**
     * Extract system prompt from messages.
     */
    public static String extractSystemPrompt(List<Message> messages) {
        return messages.stream()
            .filter(m -> m.getMessageType() == MessageType.SYSTEM)
            .map(Message::getText)
            .collect(Collectors.joining("\n"));
    }

    /**
     * Convert Spring AI messages to Claude Code format.
     * With -p flag, we send USER, ASSISTANT, and TOOL messages.
     * SYSTEM messages are handled via --append-system-prompt flag.
     */
    public static List<ClaudeMessage> convertMessages(List<Message> messages) {
        return messages.stream()
            .filter(m -> m.getMessageType() == MessageType.USER ||
                        m.getMessageType() == MessageType.ASSISTANT ||
                        m.getMessageType() == MessageType.TOOL)
            .map(SpringAiToClaudeCodeConverter::convertMessage)
            .toList();
    }

    private static ClaudeMessage convertMessage(Message message) {
        return switch (message.getMessageType()) {
            case USER -> convertUserMessage((UserMessage) message);
            case ASSISTANT -> convertAssistantMessage((AssistantMessage) message);
            case TOOL -> convertToolResponseMessage((ToolResponseMessage) message);
            default -> throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
        };
    }

    private static ClaudeMessage convertUserMessage(UserMessage message) {
        List<ContentBlock> content = new ArrayList<>();

        if (StringUtils.hasText(message.getText())) {
            content.add(new ContentBlock.Text("text", message.getText()));
        }

        // Claude Code CLI doesn't support images, so we skip media
        // In future, we could add text description of images

        if (content.isEmpty()) {
            throw new IllegalArgumentException("User message must have text content");
        }

        return new ClaudeMessage("user", content);
    }

    private static ClaudeMessage convertAssistantMessage(AssistantMessage message) {
        List<ContentBlock> content = new ArrayList<>();

        // Convert thinking blocks (must come FIRST in content array per Anthropic docs)
        Boolean hasThinking = (Boolean) message.getMetadata().get("thinking");
        if (hasThinking != null && hasThinking) {
            String signature = (String) message.getMetadata().get("signature");
            String thinkingText = message.getText();
            if (StringUtils.hasText(thinkingText) && StringUtils.hasText(signature)) {
                logger.debug("Converting thinking block: text length={}, signature={}",
                    thinkingText.length(), signature);
                content.add(new ContentBlock.Thinking("thinking", thinkingText, signature));
            }
        }

        // Add text content (after thinking if present)
        if (StringUtils.hasText(message.getText()) && !Boolean.TRUE.equals(hasThinking)) {
            content.add(new ContentBlock.Text("text", message.getText()));
        }

        // Convert tool calls to ToolUse blocks
        if (message.hasToolCalls()) {
            for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
                Map<String, Object> input = parseJsonToMap(toolCall.arguments());
                content.add(new ContentBlock.ToolUse(
                    "tool_use",
                    toolCall.id(),
                    toolCall.name(),
                    input
                ));
            }
        }

        if (content.isEmpty()) {
            throw new IllegalArgumentException("Assistant message must have content");
        }

        return new ClaudeMessage("assistant", content);
    }

    private static ClaudeMessage convertToolResponseMessage(ToolResponseMessage message) {
        List<ContentBlock> content = message.getResponses().stream()
            .map(response -> new ContentBlock.ToolResult(
                "tool_result",
                response.id(),
                response.responseData(),
                null
            ))
            .collect(Collectors.toList());

        return new ClaudeMessage("user", content);
    }

    private static Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse tool arguments JSON", e);
        }
    }
}
