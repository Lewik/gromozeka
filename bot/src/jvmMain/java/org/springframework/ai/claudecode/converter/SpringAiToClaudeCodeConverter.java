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
import org.springframework.ai.content.Media;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
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

        // Add media FIRST (per Anthropic best practices)
        if (!CollectionUtils.isEmpty(message.getMedia())) {
            for (Media media : message.getMedia()) {
                try {
                    content.add(convertMedia(media));
                } catch (IOException e) {
                    logger.error("Failed to convert media: {}", e.getMessage(), e);
                    throw new IllegalArgumentException("Failed to convert media", e);
                }
            }
        }

        // Then add text content
        if (StringUtils.hasText(message.getText())) {
            content.add(new ContentBlock.Text("text", message.getText()));
        }

        if (content.isEmpty()) {
            throw new IllegalArgumentException("User message must have text or media content");
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
            .map(response -> {
                Object responseData = response.responseData();
                Object toolResultContent = convertToolResponseData(responseData);

                return new ContentBlock.ToolResult(
                    "tool_result",
                    response.id(),
                    toolResultContent,
                    null
                );
            })
            .collect(Collectors.toList());

        return new ClaudeMessage("user", content);
    }

    @SuppressWarnings("unchecked")
    private static Object convertToolResponseData(Object responseData) {
        if (!(responseData instanceof Map)) {
            return responseData;
        }

        Map<String, Object> dataMap = (Map<String, Object>) responseData;
        String type = (String) dataMap.get("type");

        if ("image".equals(type)) {
            Map<String, Object> source = (Map<String, Object>) dataMap.get("source");
            if (source != null) {
                ContentBlock.ImageSource imageSource = new ContentBlock.ImageSource(
                    (String) source.get("type"),
                    (String) source.get("media_type"),
                    (String) source.get("data")
                );
                return List.of(new ContentBlock.Image("image", imageSource));
            }
        } else if ("document".equals(type)) {
            Map<String, Object> source = (Map<String, Object>) dataMap.get("source");
            if (source != null) {
                ContentBlock.DocumentSource documentSource = new ContentBlock.DocumentSource(
                    (String) source.get("type"),
                    (String) source.get("media_type"),
                    (String) source.get("data")
                );
                return List.of(new ContentBlock.Document("document", documentSource));
            }
        } else if (dataMap.containsKey("content")) {
            return dataMap.get("content");
        } else if (dataMap.containsKey("error")) {
            return "Error: " + dataMap.get("error");
        }

        return responseData;
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

    private static ContentBlock convertMedia(Media media) throws IOException {
        byte[] data = media.getDataAsByteArray();
        String base64 = Base64.getEncoder().encodeToString(data);
        String mimeType = media.getMimeType().toString();

        logger.debug("Converting media: type={}, size={} bytes", mimeType, data.length);

        if (media.getMimeType().getType().equals("image")) {
            return new ContentBlock.Image(
                "image",
                new ContentBlock.ImageSource("base64", mimeType, base64)
            );
        } else if (mimeType.equals("application/pdf")) {
            return new ContentBlock.Document(
                "document",
                new ContentBlock.DocumentSource("base64", mimeType, base64)
            );
        }

        throw new UnsupportedOperationException("Unsupported media type: " + mimeType);
    }
}
