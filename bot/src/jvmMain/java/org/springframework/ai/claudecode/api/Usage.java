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
 * Token usage information from Claude Code CLI response.
 * Order matches Kotlin UsageInfo structure for consistency.
 *
 * @param inputTokens Total input tokens
 * @param outputTokens Total output tokens
 * @param cacheCreationInputTokens Tokens used for cache creation
 * @param cacheReadInputTokens Tokens read from cache
 * @param cacheCreation Cache creation details
 * @param serverToolUse Server-side tool usage statistics
 * @param serviceTier Service tier used
 */
public record Usage(
    @JsonProperty("input_tokens") Integer inputTokens,
    @JsonProperty("output_tokens") Integer outputTokens,
    @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
    @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens,
    @JsonProperty("cache_creation") CacheCreation cacheCreation,
    @JsonProperty("server_tool_use") ServerToolUse serverToolUse,
    @JsonProperty("service_tier") String serviceTier
) {
    public Usage {
        inputTokens = inputTokens != null ? inputTokens : 0;
        outputTokens = outputTokens != null ? outputTokens : 0;
        cacheCreationInputTokens = cacheCreationInputTokens != null ? cacheCreationInputTokens : 0;
        cacheReadInputTokens = cacheReadInputTokens != null ? cacheReadInputTokens : 0;
    }

    public int getTotalTokens() {
        return inputTokens + outputTokens;
    }

    public record CacheCreation(
        @JsonProperty("ephemeral_5m_input_tokens") Integer ephemeral5mInputTokens,
        @JsonProperty("ephemeral_1h_input_tokens") Integer ephemeral1hInputTokens
    ) {}

    public record ServerToolUse(
        @JsonProperty("web_search_requests") Integer webSearchRequests
    ) {}
}
