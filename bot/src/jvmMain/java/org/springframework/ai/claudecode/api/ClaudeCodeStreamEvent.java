package org.springframework.ai.claudecode.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ClaudeCodeStreamEvent.InitEvent.class, name = "system"),
    @JsonSubTypes.Type(value = ClaudeCodeStreamEvent.AssistantEvent.class, name = "assistant"),
    @JsonSubTypes.Type(value = ClaudeCodeStreamEvent.ErrorEvent.class, name = "error"),
    @JsonSubTypes.Type(value = ClaudeCodeStreamEvent.ResultEvent.class, name = "result")
})
public abstract class ClaudeCodeStreamEvent {

    @JsonProperty("type")
    private String type;

    public String type() {
        return type;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InitEvent extends ClaudeCodeStreamEvent {
        @JsonProperty("subtype")
        private String subtype;

        @JsonProperty("session_id")
        private String sessionId;

        @JsonProperty("tools")
        private List<String> tools;

        @JsonProperty("mcp_servers")
        private List<String> mcpServers;

        @JsonProperty("apiKeySource")
        private String apiKeySource;

        public String subtype() { return subtype; }
        public String sessionId() { return sessionId; }
        public List<String> tools() { return tools; }
        public List<String> mcpServers() { return mcpServers; }
        public String apiKeySource() { return apiKeySource; }

        public boolean isSubscription() {
            return "none".equals(apiKeySource);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssistantEvent extends ClaudeCodeStreamEvent {
        @JsonProperty("message")
        private AnthropicMessage message;

        @JsonProperty("session_id")
        private String sessionId;

        public AnthropicMessage message() { return message; }
        public String sessionId() { return sessionId; }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class AnthropicMessage {
            @JsonProperty("id")
            private String id;

            @JsonProperty("type")
            private String type;

            @JsonProperty("role")
            private String role;

            @JsonProperty("content")
            private List<ContentBlock> content;

            @JsonProperty("model")
            private String model;

            @JsonProperty("stop_reason")
            private String stopReason;

            @JsonProperty("stop_sequence")
            private String stopSequence;

            @JsonProperty("usage")
            private Usage usage;

            public String id() { return id; }
            public String type() { return type; }
            public String role() { return role; }
            public List<ContentBlock> content() { return content; }
            public String model() { return model; }
            public String stopReason() { return stopReason; }
            public String stopSequence() { return stopSequence; }
            public Usage usage() { return usage; }

            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class ContentBlock {
                @JsonProperty("type")
                private String type;

                @JsonProperty("text")
                private String text;

                @JsonProperty("thinking")
                private String thinking;

                @JsonProperty("id")
                private String id;

                @JsonProperty("name")
                private String name;

                @JsonProperty("input")
                private Map<String, Object> input;

                public String type() { return type; }
                public String text() { return text; }
                public String thinking() { return thinking; }
                public String id() { return id; }
                public String name() { return name; }
                public Map<String, Object> input() { return input; }
            }

            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Usage {
                @JsonProperty("input_tokens")
                private Integer inputTokens;

                @JsonProperty("output_tokens")
                private Integer outputTokens;

                @JsonProperty("cache_creation_input_tokens")
                private Integer cacheCreationInputTokens;

                @JsonProperty("cache_read_input_tokens")
                private Integer cacheReadInputTokens;

                public Integer inputTokens() { return inputTokens; }
                public Integer outputTokens() { return outputTokens; }
                public Integer cacheCreationInputTokens() { return cacheCreationInputTokens; }
                public Integer cacheReadInputTokens() { return cacheReadInputTokens; }
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorEvent extends ClaudeCodeStreamEvent {
        @JsonProperty("message")
        private String message;

        public String message() { return message; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultEvent extends ClaudeCodeStreamEvent {
        @JsonProperty("subtype")
        private String subtype;

        @JsonProperty("total_cost_usd")
        private Double totalCostUsd;

        @JsonProperty("is_error")
        private Boolean isError;

        @JsonProperty("duration_ms")
        private Long durationMs;

        @JsonProperty("duration_api_ms")
        private Long durationApiMs;

        @JsonProperty("num_turns")
        private Integer numTurns;

        @JsonProperty("result")
        private String result;

        @JsonProperty("session_id")
        private String sessionId;

        public String subtype() { return subtype; }
        public Double totalCostUsd() { return totalCostUsd; }
        public Boolean isError() { return isError; }
        public Long durationMs() { return durationMs; }
        public Long durationApiMs() { return durationApiMs; }
        public Integer numTurns() { return numTurns; }
        public String result() { return result; }
        public String sessionId() { return sessionId; }
    }
}
