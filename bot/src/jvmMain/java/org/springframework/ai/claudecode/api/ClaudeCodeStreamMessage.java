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

/**
 * Wrapper for messages sent to Claude Code CLI via stdin in stream-json mode.
 *
 * @param type Message type ("user" or "control")
 * @param message The actual message content
 * @param sessionId Session identifier
 * @param parentToolUseId Parent tool use ID for nested calls
 */
public record ClaudeCodeStreamMessage(
    String type,
    ClaudeMessage message,
    @JsonProperty("session_id") String sessionId,
    @JsonProperty("parent_tool_use_id") String parentToolUseId
) {
    public static ClaudeCodeStreamMessage user(ClaudeMessage message, String sessionId) {
        return new ClaudeCodeStreamMessage("user", message, sessionId, null);
    }

    public static ClaudeCodeStreamMessage control(ClaudeMessage message, String sessionId) {
        return new ClaudeCodeStreamMessage("control", message, sessionId, null);
    }
}
