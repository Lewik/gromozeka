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

package org.springframework.ai.claudecode.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * Sealed interface representing different content block types in Claude Code CLI responses.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContentBlock.Text.class, name = "text"),
    @JsonSubTypes.Type(value = ContentBlock.TextDelta.class, name = "text_delta"),
    @JsonSubTypes.Type(value = ContentBlock.Thinking.class, name = "thinking"),
    @JsonSubTypes.Type(value = ContentBlock.ThinkingDelta.class, name = "thinking_delta"),
    @JsonSubTypes.Type(value = ContentBlock.RedactedThinking.class, name = "redacted_thinking"),
    @JsonSubTypes.Type(value = ContentBlock.ToolUse.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ContentBlock.ToolResult.class, name = "tool_result"),
    @JsonSubTypes.Type(value = ContentBlock.Image.class, name = "image"),
    @JsonSubTypes.Type(value = ContentBlock.Document.class, name = "document")
})
public sealed interface ContentBlock permits
    ContentBlock.Text,
    ContentBlock.TextDelta,
    ContentBlock.Thinking,
    ContentBlock.ThinkingDelta,
    ContentBlock.RedactedThinking,
    ContentBlock.ToolUse,
    ContentBlock.ToolResult,
    ContentBlock.Image,
    ContentBlock.Document {

    String type();

    record Text(
        String type,
        String text
    ) implements ContentBlock {}

    record TextDelta(
        String type,
        String text
    ) implements ContentBlock {}

    record Thinking(
        String type,
        String thinking,
        String signature
    ) implements ContentBlock {}

    record ThinkingDelta(
        String type,
        String thinking
    ) implements ContentBlock {}

    record RedactedThinking(
        String type,
        String data
    ) implements ContentBlock {}

    record ToolUse(
        String type,
        String id,
        String name,
        Map<String, Object> input
    ) implements ContentBlock {}

    record ToolResult(
        String type,
        @JsonProperty("tool_use_id") String toolUseId,
        Object content,
        @JsonProperty("is_error") Boolean isError
    ) implements ContentBlock {}

    record Image(
        String type,
        ImageSource source
    ) implements ContentBlock {}

    record ImageSource(
        String type,
        @JsonProperty("media_type") String mediaType,
        String data
    ) {}

    record Document(
        String type,
        DocumentSource source
    ) implements ContentBlock {}

    record DocumentSource(
        String type,
        @JsonProperty("media_type") String mediaType,
        String data
    ) {}
}
