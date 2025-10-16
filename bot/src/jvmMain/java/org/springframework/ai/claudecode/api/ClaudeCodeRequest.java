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
 * Request to Claude Code CLI.
 *
 * @param model Model identifier
 * @param messages List of messages in conversation
 * @param systemPrompt System prompt for the conversation
 * @param maxTokens Maximum tokens to generate
 * @param temperature Temperature for randomness (0.0-1.0)
 * @param thinkingBudgetTokens Budget for thinking tokens
 * @param disallowedTools List of tools to disable in Claude Code CLI
 */
public record ClaudeCodeRequest(
    String model,
    List<ClaudeMessage> messages,
    String systemPrompt,
    Integer maxTokens,
    Double temperature,
    Integer thinkingBudgetTokens,
    List<String> disallowedTools
) {
    public ClaudeCodeRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model cannot be null or blank");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Messages cannot be null or empty");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model;
        private List<ClaudeMessage> messages;
        private String systemPrompt = "";
        private Integer maxTokens = 4096;
        private Double temperature = 0.7;
        private Integer thinkingBudgetTokens = 0;
        private List<String> disallowedTools = List.of();

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<ClaudeMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder thinkingBudgetTokens(Integer thinkingBudgetTokens) {
            this.thinkingBudgetTokens = thinkingBudgetTokens;
            return this;
        }

        public Builder disallowedTools(List<String> disallowedTools) {
            this.disallowedTools = disallowedTools;
            return this;
        }

        public ClaudeCodeRequest build() {
            return new ClaudeCodeRequest(
                model,
                messages,
                systemPrompt,
                maxTokens,
                temperature,
                thinkingBudgetTokens,
                disallowedTools
            );
        }
    }
}
