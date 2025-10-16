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

package org.springframework.ai.claudecode;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * Configuration options for Claude Code ChatModel.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClaudeCodeChatOptions implements ToolCallingChatOptions {

    public static final String DEFAULT_MODEL = "claude-sonnet-4-5-20250929";
    public static final Integer DEFAULT_MAX_TOKENS = 4096;
    public static final Double DEFAULT_TEMPERATURE = 0.7;

    @JsonProperty("model")
    private String model;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("temperature")
    private Double temperature;

    @JsonProperty("thinking_budget_tokens")
    private Integer thinkingBudgetTokens;

    @JsonIgnore
    private Boolean internalToolExecutionEnabled;

    @JsonIgnore
    private Set<String> toolNames;

    @JsonIgnore
    private List<ToolCallback> toolCallbacks;

    @JsonIgnore
    private Map<String, Object> toolContext;

    @JsonIgnore
    private Boolean useXmlToolFormat;

    @JsonIgnore
    private List<String> disallowedTools;

    public ClaudeCodeChatOptions() {
        this.model = DEFAULT_MODEL;
        this.maxTokens = DEFAULT_MAX_TOKENS;
        this.temperature = DEFAULT_TEMPERATURE;
        this.thinkingBudgetTokens = 0;
        this.internalToolExecutionEnabled = true;
        this.toolNames = new HashSet<>();
        this.toolCallbacks = new ArrayList<>();
        this.toolContext = new HashMap<>();
        this.useXmlToolFormat = false;
        this.disallowedTools = new ArrayList<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ClaudeCodeChatOptions fromOptions(ClaudeCodeChatOptions fromOptions) {
        ClaudeCodeChatOptions options = new ClaudeCodeChatOptions();
        options.setModel(fromOptions.getModel());
        options.setMaxTokens(fromOptions.getMaxTokens());
        options.setTemperature(fromOptions.getTemperature());
        options.setThinkingBudgetTokens(fromOptions.getThinkingBudgetTokens());
        options.setInternalToolExecutionEnabled(fromOptions.getInternalToolExecutionEnabled());
        options.setToolNames(fromOptions.getToolNames());
        options.setToolCallbacks(fromOptions.getToolCallbacks());
        options.setToolContext(fromOptions.getToolContext());
        options.setUseXmlToolFormat(fromOptions.getUseXmlToolFormat());
        options.setDisallowedTools(fromOptions.getDisallowedTools());
        return options;
    }

    @Override
    public ClaudeCodeChatOptions copy() {
        return fromOptions(this);
    }

    public String getModel() {
        return model;
    }

    @Override
    public Double getFrequencyPenalty() {
        return null;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    @Override
    public Double getPresencePenalty() {
        return null;
    }

    @Override
    public List<String> getStopSequences() {
        return null;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    @Override
    @Nullable
    public Double getTemperature() {
        return temperature;
    }

    @Override
    public Integer getTopK() {
        return null;
    }

    @Override
    public Double getTopP() {
        return null;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getThinkingBudgetTokens() {
        return thinkingBudgetTokens;
    }

    public void setThinkingBudgetTokens(Integer thinkingBudgetTokens) {
        this.thinkingBudgetTokens = thinkingBudgetTokens;
    }

    @Override
    @Nullable
    public Boolean getInternalToolExecutionEnabled() {
        return internalToolExecutionEnabled;
    }

    public void setInternalToolExecutionEnabled(Boolean internalToolExecutionEnabled) {
        this.internalToolExecutionEnabled = internalToolExecutionEnabled;
    }

    @Override
    @Nullable
    public Set<String> getToolNames() {
        return toolNames;
    }

    public void setToolNames(Set<String> toolNames) {
        this.toolNames = toolNames != null ? toolNames : new HashSet<>();
    }

    @Override
    @Nullable
    public List<ToolCallback> getToolCallbacks() {
        return toolCallbacks;
    }

    public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : new ArrayList<>();
    }

    @Override
    @Nullable
    public Map<String, Object> getToolContext() {
        return toolContext;
    }

    public void setToolContext(Map<String, Object> toolContext) {
        this.toolContext = toolContext != null ? toolContext : new HashMap<>();
    }

    @Nullable
    public Boolean getUseXmlToolFormat() {
        return useXmlToolFormat;
    }

    public void setUseXmlToolFormat(Boolean useXmlToolFormat) {
        this.useXmlToolFormat = useXmlToolFormat;
    }

    @Nullable
    public List<String> getDisallowedTools() {
        return disallowedTools;
    }

    public void setDisallowedTools(List<String> disallowedTools) {
        this.disallowedTools = disallowedTools != null ? disallowedTools : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClaudeCodeChatOptions that)) return false;
        return Objects.equals(model, that.model) &&
                Objects.equals(maxTokens, that.maxTokens) &&
                Objects.equals(temperature, that.temperature) &&
                Objects.equals(thinkingBudgetTokens, that.thinkingBudgetTokens) &&
                Objects.equals(internalToolExecutionEnabled, that.internalToolExecutionEnabled) &&
                Objects.equals(useXmlToolFormat, that.useXmlToolFormat) &&
                Objects.equals(disallowedTools, that.disallowedTools);
    }

    @Override
    public int hashCode() {
        return Objects.hash(model, maxTokens, temperature, thinkingBudgetTokens,
                           internalToolExecutionEnabled, useXmlToolFormat, disallowedTools);
    }

    @Override
    public String toString() {
        return "ClaudeCodeChatOptions{" +
                "model='" + model + '\'' +
                ", maxTokens=" + maxTokens +
                ", temperature=" + temperature +
                ", thinkingBudgetTokens=" + thinkingBudgetTokens +
                ", internalToolExecutionEnabled=" + internalToolExecutionEnabled +
                ", useXmlToolFormat=" + useXmlToolFormat +
                ", disallowedTools=" + disallowedTools +
                '}';
    }

    public static class Builder {
        private ClaudeCodeChatOptions options = new ClaudeCodeChatOptions();

        public Builder model(String model) {
            options.setModel(model);
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            options.setMaxTokens(maxTokens);
            return this;
        }

        public Builder temperature(Double temperature) {
            options.setTemperature(temperature);
            return this;
        }

        public Builder thinkingBudgetTokens(Integer thinkingBudgetTokens) {
            options.setThinkingBudgetTokens(thinkingBudgetTokens);
            return this;
        }

        public Builder internalToolExecutionEnabled(Boolean enabled) {
            options.setInternalToolExecutionEnabled(enabled);
            return this;
        }

        public Builder toolNames(Set<String> toolNames) {
            options.setToolNames(toolNames);
            return this;
        }

        public Builder toolCallbacks(List<ToolCallback> toolCallbacks) {
            options.setToolCallbacks(toolCallbacks);
            return this;
        }

        public Builder toolContext(Map<String, Object> toolContext) {
            options.setToolContext(toolContext);
            return this;
        }

        public Builder useXmlToolFormat(Boolean useXmlToolFormat) {
            options.setUseXmlToolFormat(useXmlToolFormat);
            return this;
        }

        public Builder disallowedTools(List<String> disallowedTools) {
            options.setDisallowedTools(disallowedTools);
            return this;
        }

        public ClaudeCodeChatOptions build() {
            return options;
        }
    }
}
