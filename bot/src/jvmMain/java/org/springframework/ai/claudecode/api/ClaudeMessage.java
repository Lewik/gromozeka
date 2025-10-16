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

import java.util.List;

/**
 * Message in Claude Code CLI format.
 *
 * @param role Message role (user, assistant)
 * @param content List of content blocks
 */
public record ClaudeMessage(
    String role,
    List<ContentBlock> content
) {
    public ClaudeMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("Role cannot be null or blank");
        }
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("Content cannot be null or empty");
        }
    }

    public static ClaudeMessage user(String text) {
        return new ClaudeMessage("user", List.of(new ContentBlock.Text("text", text)));
    }

    public static ClaudeMessage assistant(String text) {
        return new ClaudeMessage("assistant", List.of(new ContentBlock.Text("text", text)));
    }
}
