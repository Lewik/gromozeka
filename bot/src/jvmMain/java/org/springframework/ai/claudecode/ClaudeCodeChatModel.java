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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.claudecode.api.ClaudeCodeApi;
import org.springframework.ai.claudecode.api.ClaudeCodeRequest;
import org.springframework.ai.claudecode.api.ClaudeCodeStreamResponse;
import org.springframework.ai.claudecode.api.ClaudeMessage;
import org.springframework.ai.claudecode.converter.ClaudeCodeToSpringAiConverter;
import org.springframework.ai.claudecode.converter.SpringAiToClaudeCodeConverter;
import org.springframework.ai.claudecode.xml.SystemPromptEnhancer;
import org.springframework.ai.claudecode.xml.XmlToolParser;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.support.UsageCalculator;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Spring AI ChatModel implementation for Claude Code CLI.
 *
 * <p>Provides standard ChatModel interface for Claude Code CLI with:
 * <ul>
 *   <li>Streaming and blocking chat APIs</li>
 *   <li>Tool calling via Spring AI {@link ToolCallingManager}</li>
 *   <li>Optional XML-based tool invocation format</li>
 *   <li>Observation/metrics integration</li>
 *   <li>Retry support for transient failures</li>
 * </ul>
 *
 * @author Gromozeka Team
 * @since 1.0.0
 */
public class ClaudeCodeChatModel implements ChatModel {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeCodeChatModel.class);

    private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION =
        new DefaultChatModelObservationConvention();

    private static final ToolCallingManager DEFAULT_TOOL_CALLING_MANAGER =
        ToolCallingManager.builder().build();

    private final ClaudeCodeApi claudeCodeApi;
    private final ClaudeCodeChatOptions defaultOptions;
    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate;
    private final SystemPromptEnhancer systemPromptEnhancer;

    private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

    public ClaudeCodeChatModel(
            ClaudeCodeApi claudeCodeApi,
            ClaudeCodeChatOptions defaultOptions,
            ToolCallingManager toolCallingManager,
            RetryTemplate retryTemplate,
            ObservationRegistry observationRegistry,
            ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate) {

        Assert.notNull(claudeCodeApi, "claudeCodeApi cannot be null");
        Assert.notNull(defaultOptions, "defaultOptions cannot be null");
        Assert.notNull(toolCallingManager, "toolCallingManager cannot be null");
        Assert.notNull(retryTemplate, "retryTemplate cannot be null");
        Assert.notNull(observationRegistry, "observationRegistry cannot be null");
        Assert.notNull(toolExecutionEligibilityPredicate, "toolExecutionEligibilityPredicate cannot be null");

        this.claudeCodeApi = claudeCodeApi;
        this.defaultOptions = defaultOptions;
        this.toolCallingManager = toolCallingManager;
        this.retryTemplate = retryTemplate;
        this.observationRegistry = observationRegistry;
        this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate;
        this.systemPromptEnhancer = new SystemPromptEnhancer();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Prompt requestPrompt = buildRequestPrompt(prompt);

        // Use MessageAggregator callback to get aggregated response with tool calls
        // instead of blockLast() which only returns the last element
        AtomicReference<ChatResponse> aggregatedResponse = new AtomicReference<>();

        stream(requestPrompt)
            .transform(flux -> new MessageAggregator().aggregate(flux, aggregatedResponse::set))
            .blockLast();

        return aggregatedResponse.get();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Prompt requestPrompt = buildRequestPrompt(prompt);
        return internalStream(requestPrompt, null);
    }

    private Flux<ChatResponse> internalStream(Prompt prompt, ChatResponse previousResponse) {
        return Flux.deferContextual(contextView -> {
            ClaudeCodeRequest request = createRequest(prompt);

            ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider("Claude Code CLI")
                .build();

            Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
                this.observationConvention,
                DEFAULT_OBSERVATION_CONVENTION,
                () -> observationContext,
                this.observationRegistry);

            observation.parentObservation(
                contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null)).start();

            AtomicReference<org.springframework.ai.claudecode.api.Usage> usageAccumulator =
                new AtomicReference<>(new org.springframework.ai.claudecode.api.Usage(0, 0, 0, 0, null, null, null));

            Flux<ChatResponse> responseFlux = claudeCodeApi.chatCompletionStream(request)
                .flatMap(streamResponse -> {
                    if (streamResponse instanceof ClaudeCodeStreamResponse.AssistantMessage assistantMessage) {
                        // Accumulate usage
                        if (assistantMessage.message().usage() != null) {
                            usageAccumulator.set(assistantMessage.message().usage());
                        }

                        // Convert to ChatResponse
                        ChatResponse chatResponse = ClaudeCodeToSpringAiConverter.convertToChatResponse(
                            assistantMessage,
                            usageAccumulator.get()
                        );

                        // Parse XML tools if XML mode is enabled
                        ClaudeCodeChatOptions options = (ClaudeCodeChatOptions) prompt.getOptions();
                        if (Boolean.TRUE.equals(options.getUseXmlToolFormat())) {
                            chatResponse = parseXmlToolsAndEnhanceResponse(chatResponse, options);
                        }

                        // Check if tool execution is required
                        if (toolExecutionEligibilityPredicate.isToolExecutionRequired(prompt.getOptions(), chatResponse)) {
                            if (chatResponse.hasFinishReasons(Set.of("tool_use"))) {
                                ToolExecutionResult toolExecutionResult =
                                    toolCallingManager.executeToolCalls(prompt, chatResponse);

                                if (toolExecutionResult.returnDirect()) {
                                    return Flux.just(ChatResponse.builder()
                                        .from(chatResponse)
                                        .generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
                                        .build());
                                } else {
                                    // Emit intermediate message with tool calls, then recurse
                                    // This allows MessageAggregator to see tool execution
                                    return Flux.concat(
                                        Flux.just(chatResponse),  // Emit tool_use message
                                        internalStream(
                                            new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()),
                                            chatResponse
                                        )
                                    );
                                }
                            }
                        }

                        return Flux.just(chatResponse);
                    } else {
                        // Skip non-assistant messages (system, result, error handled in API layer)
                        return Flux.empty();
                    }
                })
                .doOnError(observation::error)
                .doFinally(s -> observation.stop())
                .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));

            return new MessageAggregator().aggregate(responseFlux, observationContext::setResponse);
        });
    }

    private Prompt buildRequestPrompt(Prompt prompt) {
        ClaudeCodeChatOptions runtimeOptions = null;

        if (prompt.getOptions() != null) {
            if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions) {
                runtimeOptions = ModelOptionsUtils.copyToTarget(
                    toolCallingChatOptions,
                    ToolCallingChatOptions.class,
                    ClaudeCodeChatOptions.class
                );
            } else {
                runtimeOptions = ModelOptionsUtils.copyToTarget(
                    prompt.getOptions(),
                    ChatOptions.class,
                    ClaudeCodeChatOptions.class
                );
            }
        }

        ClaudeCodeChatOptions requestOptions = ModelOptionsUtils.merge(
            runtimeOptions,
            this.defaultOptions,
            ClaudeCodeChatOptions.class
        );

        // Merge tool-related options explicitly
        if (runtimeOptions != null) {
            requestOptions.setInternalToolExecutionEnabled(
                ModelOptionsUtils.mergeOption(
                    runtimeOptions.getInternalToolExecutionEnabled(),
                    this.defaultOptions.getInternalToolExecutionEnabled()
                )
            );
            requestOptions.setToolNames(
                ToolCallingChatOptions.mergeToolNames(
                    runtimeOptions.getToolNames(),
                    this.defaultOptions.getToolNames()
                )
            );
            requestOptions.setToolCallbacks(
                ToolCallingChatOptions.mergeToolCallbacks(
                    runtimeOptions.getToolCallbacks(),
                    this.defaultOptions.getToolCallbacks()
                )
            );
            requestOptions.setToolContext(
                ToolCallingChatOptions.mergeToolContext(
                    runtimeOptions.getToolContext(),
                    this.defaultOptions.getToolContext()
                )
            );
            requestOptions.setUseXmlToolFormat(
                ModelOptionsUtils.mergeOption(
                    runtimeOptions.getUseXmlToolFormat(),
                    this.defaultOptions.getUseXmlToolFormat()
                )
            );
            requestOptions.setDisallowedTools(
                runtimeOptions.getDisallowedTools() != null && !runtimeOptions.getDisallowedTools().isEmpty()
                    ? runtimeOptions.getDisallowedTools()
                    : this.defaultOptions.getDisallowedTools()
            );
        }

        ToolCallingChatOptions.validateToolCallbacks(requestOptions.getToolCallbacks());

        return new Prompt(prompt.getInstructions(), requestOptions);
    }

    private ClaudeCodeRequest createRequest(Prompt prompt) {
        ClaudeCodeChatOptions options = (ClaudeCodeChatOptions) prompt.getOptions();

        String systemPrompt = SpringAiToClaudeCodeConverter.extractSystemPrompt(prompt.getInstructions());
        List<ClaudeMessage> messages = SpringAiToClaudeCodeConverter.convertMessages(prompt.getInstructions());

        // Auto-enable extended thinking based on instruction level
        // Token budgets based on Claude Code CLI standards:
        // - think: 4,000 tokens
        // - think_hard/megathink: 10,000 tokens
        // - ultrathink: 31,999 tokens
        Integer thinkingBudget = options.getThinkingBudgetTokens();
        if ((thinkingBudget == null || thinkingBudget == 0) && systemPrompt != null) {
            if (systemPrompt.contains("thinking_ultrathink")) {
                logger.debug("Detected thinking_ultrathink instruction, auto-enabling extended thinking with 31999 tokens");
                thinkingBudget = 31999;  // UltraThink level
            } else if (systemPrompt.contains("thinking_think_harder") || systemPrompt.contains("thinking_megathink")) {
                logger.debug("Detected think_harder/megathink instruction, auto-enabling extended thinking with 10000 tokens");
                thinkingBudget = 10000;  // Think Hard level
            } else if (systemPrompt.contains("thinking_think")) {
                logger.debug("Detected thinking_think instruction, auto-enabling extended thinking with 4000 tokens");
                thinkingBudget = 4000;   // Think level
            }
        }

        // Enhance system prompt with XML tool descriptions if XML mode enabled
        if (Boolean.TRUE.equals(options.getUseXmlToolFormat())) {
            // Resolve tool names to tool definitions via toolCallingManager
            List<ToolDefinition> toolDefinitions = this.toolCallingManager.resolveToolDefinitions(options);

            if (!toolDefinitions.isEmpty()) {
                logger.debug("XML tool format enabled, enhancing system prompt with {} tools", toolDefinitions.size());

                systemPrompt = systemPromptEnhancer.enhanceWithXmlTools(
                    systemPrompt,
                    toolDefinitions
                );
            }
        }

        return ClaudeCodeRequest.builder()
            .model(options.getModel())
            .messages(messages)
            .systemPrompt(systemPrompt)
            .maxTokens(options.getMaxTokens())
            .temperature(options.getTemperature())
            .thinkingBudgetTokens(thinkingBudget)
            .disallowedTools(options.getDisallowedTools())
            .projectPath(options.getProjectPath())
            .build();
    }

    private ChatResponse parseXmlToolsAndEnhanceResponse(
            ChatResponse originalResponse,
            ClaudeCodeChatOptions options) {

        if (originalResponse.getResults().isEmpty()) {
            return originalResponse;
        }

        var generation = originalResponse.getResult();
        var assistantMessage = generation.getOutput();
        String content = assistantMessage.getText();

        if (content == null || content.isEmpty()) {
            return originalResponse;
        }

        // Resolve tool definitions to get registered tool names
        List<ToolDefinition> toolDefinitions = this.toolCallingManager.resolveToolDefinitions(options);
        Set<String> registeredToolNames = toolDefinitions.stream()
            .map(ToolDefinition::name)
            .collect(Collectors.toSet());

        XmlToolParser parser = new XmlToolParser(registeredToolNames);
        XmlToolParser.ParsedMessage parsed = parser.parse(content);

        if (!parsed.hasToolCalls()) {
            return originalResponse;
        }

        logger.debug("Parsed {} XML tool calls from assistant message", parsed.getToolCalls().size());

        AssistantMessage newAssistantMessage = new AssistantMessage(
            parsed.getTextContent(),
            assistantMessage.getMetadata(),
            parsed.getToolCalls()
        );

        Generation newGeneration = new Generation(
            newAssistantMessage,
            ChatGenerationMetadata.builder()
                .finishReason("tool_use")
                .build()
        );

        return ChatResponse.builder()
            .from(originalResponse)
            .generations(List.of(newGeneration))
            .build();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ClaudeCodeChatOptions.fromOptions(this.defaultOptions);
    }

    public void setObservationConvention(ChatModelObservationConvention observationConvention) {
        Assert.notNull(observationConvention, "observationConvention cannot be null");
        this.observationConvention = observationConvention;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ClaudeCodeApi claudeCodeApi;
        private ClaudeCodeChatOptions defaultOptions = ClaudeCodeChatOptions.builder().build();
        private RetryTemplate retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE;
        private ToolCallingManager toolCallingManager;
        private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;
        private ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate =
            new DefaultToolExecutionEligibilityPredicate();

        private Builder() {}

        public Builder claudeCodeApi(ClaudeCodeApi claudeCodeApi) {
            this.claudeCodeApi = claudeCodeApi;
            return this;
        }

        public Builder defaultOptions(ClaudeCodeChatOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        public Builder retryTemplate(RetryTemplate retryTemplate) {
            this.retryTemplate = retryTemplate;
            return this;
        }

        public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
            this.toolCallingManager = toolCallingManager;
            return this;
        }

        public Builder observationRegistry(ObservationRegistry observationRegistry) {
            this.observationRegistry = observationRegistry;
            return this;
        }

        public Builder toolExecutionEligibilityPredicate(
                ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate) {
            this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate;
            return this;
        }

        public ClaudeCodeChatModel build() {
            if (this.toolCallingManager != null) {
                return new ClaudeCodeChatModel(
                    this.claudeCodeApi,
                    this.defaultOptions,
                    this.toolCallingManager,
                    this.retryTemplate,
                    this.observationRegistry,
                    this.toolExecutionEligibilityPredicate
                );
            }
            return new ClaudeCodeChatModel(
                this.claudeCodeApi,
                this.defaultOptions,
                DEFAULT_TOOL_CALLING_MANAGER,
                this.retryTemplate,
                this.observationRegistry,
                this.toolExecutionEligibilityPredicate
            );
        }
    }
}
