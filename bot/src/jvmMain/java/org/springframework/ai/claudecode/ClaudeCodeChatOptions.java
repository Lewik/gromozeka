package org.springframework.ai.claudecode;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@JsonInclude(Include.NON_NULL)
public class ClaudeCodeChatOptions implements ToolCallingChatOptions {

    private @JsonProperty("model") String model;
    private @JsonProperty("temperature") Double temperature;
    private @JsonProperty("max_tokens") Integer maxTokens;
    private @JsonProperty("thinking_budget_tokens") Integer thinkingBudgetTokens;
    private @JsonProperty("cli_path") String cliPath;
    private @JsonProperty("working_directory") String workingDirectory;

    @JsonIgnore
    private Boolean internalToolExecutionEnabled;

    @JsonIgnore
    private Set<String> toolNames = new HashSet<>();

    @JsonIgnore
    private List<ToolCallback> toolCallbacks = new ArrayList<>();

    @JsonIgnore
    private Map<String, Object> toolContext = new HashMap<>();

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    @Override
    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    @Override
    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    @Override
    public Double getTopP() {
        return null;
    }

    @Override
    public Integer getTopK() {
        return null;
    }

    @Override
    public Double getFrequencyPenalty() {
        return null;
    }

    @Override
    public Double getPresencePenalty() {
        return null;
    }

    @Override
    public List<String> getStopSequences() {
        return null;
    }

    public Integer getThinkingBudgetTokens() {
        return thinkingBudgetTokens;
    }

    public void setThinkingBudgetTokens(Integer thinkingBudgetTokens) {
        this.thinkingBudgetTokens = thinkingBudgetTokens;
    }

    public String getCliPath() {
        return cliPath;
    }

    public void setCliPath(String cliPath) {
        this.cliPath = cliPath;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    @Override
    public Boolean getInternalToolExecutionEnabled() {
        return internalToolExecutionEnabled;
    }

    public void setInternalToolExecutionEnabled(Boolean internalToolExecutionEnabled) {
        this.internalToolExecutionEnabled = internalToolExecutionEnabled;
    }

    @Override
    public Set<String> getToolNames() {
        return toolNames;
    }

    @Override
    public void setToolNames(Set<String> toolNames) {
        this.toolNames = toolNames;
    }

    @Override
    public List<ToolCallback> getToolCallbacks() {
        return toolCallbacks;
    }

    @Override
    public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
        this.toolCallbacks = toolCallbacks;
    }

    @Override
    public Map<String, Object> getToolContext() {
        return toolContext;
    }

    @Override
    public void setToolContext(Map<String, Object> toolContext) {
        this.toolContext = toolContext;
    }

    @Override
    public ChatOptions copy() {
        ClaudeCodeChatOptions copy = new ClaudeCodeChatOptions();
        copy.model = this.model;
        copy.temperature = this.temperature;
        copy.maxTokens = this.maxTokens;
        copy.thinkingBudgetTokens = this.thinkingBudgetTokens;
        copy.cliPath = this.cliPath;
        copy.workingDirectory = this.workingDirectory;
        copy.internalToolExecutionEnabled = this.internalToolExecutionEnabled;
        copy.toolNames = this.toolNames;
        copy.toolCallbacks = this.toolCallbacks;
        copy.toolContext = this.toolContext;
        return copy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ClaudeCodeChatOptions options = new ClaudeCodeChatOptions();

        public Builder model(String model) {
            options.model = model;
            return this;
        }

        public Builder temperature(Double temperature) {
            options.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            options.maxTokens = maxTokens;
            return this;
        }

        public Builder thinkingBudgetTokens(Integer thinkingBudgetTokens) {
            options.thinkingBudgetTokens = thinkingBudgetTokens;
            return this;
        }

        public Builder cliPath(String cliPath) {
            options.cliPath = cliPath;
            return this;
        }

        public Builder workingDirectory(String workingDirectory) {
            options.workingDirectory = workingDirectory;
            return this;
        }

        public Builder internalToolExecutionEnabled(Boolean enabled) {
            options.internalToolExecutionEnabled = enabled;
            return this;
        }

        public ClaudeCodeChatOptions build() {
            return options;
        }
    }
}
