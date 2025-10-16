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

import java.util.List;

/**
 * Sealed interface representing different response types from Claude Code CLI stream.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ClaudeCodeStreamResponse.SystemInit.class, name = "system"),
    @JsonSubTypes.Type(value = ClaudeCodeStreamResponse.UserMessage.class, name = "user"),
    @JsonSubTypes.Type(value = ClaudeCodeStreamResponse.AssistantMessage.class, name = "assistant"),
    @JsonSubTypes.Type(value = ClaudeCodeStreamResponse.Result.class, name = "result"),
    @JsonSubTypes.Type(value = ClaudeCodeStreamResponse.Error.class, name = "error")
})
public sealed interface ClaudeCodeStreamResponse permits
    ClaudeCodeStreamResponse.SystemInit,
    ClaudeCodeStreamResponse.UserMessage,
    ClaudeCodeStreamResponse.AssistantMessage,
    ClaudeCodeStreamResponse.Result,
    ClaudeCodeStreamResponse.Error {

    String type();

    /**
     * System initialization message.
     */
    record SystemInit(
        String type,
        String subtype,
        @JsonProperty("session_id") String sessionId,
        String apiKeySource,
        String cwd,
        List<String> tools,
        @JsonProperty("mcp_servers") List<Object> mcpServers,
        String model,
        String permissionMode,
        @JsonProperty("slash_commands") List<String> slashCommands,
        @JsonProperty("output_style") String outputStyle,
        List<String> agents,
        String uuid
    ) implements ClaudeCodeStreamResponse {}

    /**
     * User message with tool results or other user content.
     */
    record UserMessage(
        String type,
        Message message,
        @JsonProperty("parent_tool_use_id") String parentToolUseId,
        @JsonProperty("session_id") String sessionId,
        String uuid
    ) implements ClaudeCodeStreamResponse {

        public record Message(
            String role,
            List<ContentBlock> content
        ) {}
    }

    /**
     * Assistant response message with content.
     */
    record AssistantMessage(
        String type,
        Message message,
        @JsonProperty("parent_tool_use_id") String parentToolUseId,
        @JsonProperty("session_id") String sessionId,
        String uuid
    ) implements ClaudeCodeStreamResponse {

        public record Message(
            String id,
            String type,
            String role,
            List<ContentBlock> content,
            String model,
            @JsonProperty("stop_reason") String stopReason,
            @JsonProperty("stop_sequence") String stopSequence,
            Usage usage
        ) {}
    }

    /**
     * Final result with cost information.
     */
    record Result(
        String type,
        String subtype,
        @JsonProperty("is_error") Boolean isError,
        @JsonProperty("duration_ms") Integer durationMs,
        @JsonProperty("duration_api_ms") Integer durationApiMs,
        @JsonProperty("num_turns") Integer numTurns,
        String result,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("total_cost_usd") Double totalCostUsd,
        Usage usage,
        Object modelUsage,
        @JsonProperty("permission_denials") List<Object> permissionDenials,
        String uuid
    ) implements ClaudeCodeStreamResponse {}

    /**
     * Error response.
     */
    record Error(
        String type,
        String error,
        String message
    ) implements ClaudeCodeStreamResponse {}
}
