package org.springframework.ai.claudecode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ClaudeCodeChatModel implements ChatModel {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeCodeChatModel.class);

    private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION =
        new DefaultChatModelObservationConvention();

    private final ClaudeCodeApi claudeCodeApi;
    private final ClaudeCodeChatOptions defaultOptions;
    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate;

    private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

    public ClaudeCodeChatModel(
            ClaudeCodeApi claudeCodeApi,
            ClaudeCodeChatOptions defaultOptions,
            ToolCallingManager toolCallingManager,
            RetryTemplate retryTemplate,
            ObservationRegistry observationRegistry) {
        this(claudeCodeApi, defaultOptions, toolCallingManager, retryTemplate, observationRegistry,
             new DefaultToolExecutionEligibilityPredicate());
    }

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
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        throw new UnsupportedOperationException(
            "Claude Code CLI only supports streaming. Use stream() method instead.");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Prompt requestPrompt = buildRequestPrompt(prompt);
        return internalStream(requestPrompt, null);
    }

    public Flux<ChatResponse> internalStream(Prompt prompt, ChatResponse previousChatResponse) {
        return Flux.deferContextual(contextView -> {
            ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider("ClaudeCode")
                .build();

            Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
                this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
                this.observationRegistry);

            observation.parentObservation(contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null)).start();

            String systemPrompt = extractSystemPrompt(prompt);
            List<AnthropicApi.AnthropicMessage> anthropicMessages = buildAnthropicMessages(prompt);

            ClaudeCodeChatOptions options = (ClaudeCodeChatOptions) prompt.getOptions();
            if (options == null) {
                options = this.defaultOptions;
            }

            Flux<AnthropicApi.ChatCompletionResponse> apiResponses = this.claudeCodeApi.chatCompletionStream(
                systemPrompt,
                anthropicMessages,
                options.getModel(),
                options.getMaxTokens(),
                options.getTemperature(),
                options.getThinkingBudgetTokens(),
                options.getToolCallbacks()
            );

            Flux<ChatResponse> chatResponseFlux = apiResponses.flatMap(apiResponse -> {
                Usage currentUsage = apiResponse.usage() != null ? convertUsage(apiResponse.usage()) : new EmptyUsage();
                Usage accumulatedUsage = previousChatResponse != null
                    ? new DefaultUsage(
                        currentUsage.getPromptTokens() + previousChatResponse.getMetadata().getUsage().getPromptTokens(),
                        currentUsage.getCompletionTokens() + previousChatResponse.getMetadata().getUsage().getCompletionTokens()
                    )
                    : currentUsage;

                ChatResponse chatResponse = toChatResponse(apiResponse, accumulatedUsage);

                if (this.toolExecutionEligibilityPredicate.isToolExecutionRequired(prompt.getOptions(), chatResponse)) {
                    if (chatResponse.hasFinishReasons(Set.of("tool_use"))) {
                        return Flux.deferContextual(ctx -> {
                            ToolExecutionResult toolExecutionResult;
                            try {
                                ToolCallReactiveContextHolder.setContext(ctx);
                                toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, chatResponse);
                            } finally {
                                ToolCallReactiveContextHolder.clearContext();
                            }

                            if (toolExecutionResult.returnDirect()) {
                                return Flux.just(ChatResponse.builder().from(chatResponse)
                                    .generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
                                    .build());
                            } else {
                                return this.internalStream(
                                    new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()),
                                    chatResponse
                                );
                            }
                        }).subscribeOn(Schedulers.boundedElastic());
                    } else {
                        return Mono.empty();
                    }
                } else {
                    return Mono.just(chatResponse);
                }
            })
            .doOnError(observation::error)
            .doFinally(s -> observation.stop())
            .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));

            return new MessageAggregator().aggregate(chatResponseFlux, observationContext::setResponse);
        });
    }

    private Prompt buildRequestPrompt(Prompt prompt) {
        ClaudeCodeChatOptions runtimeOptions = null;
        if (prompt.getOptions() != null) {
            if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions) {
                runtimeOptions = ModelOptionsUtils.copyToTarget(toolCallingChatOptions, ToolCallingChatOptions.class,
                    ClaudeCodeChatOptions.class);
            }
            else {
                runtimeOptions = ModelOptionsUtils.copyToTarget(prompt.getOptions(), ChatOptions.class,
                    ClaudeCodeChatOptions.class);
            }
        }

        ClaudeCodeChatOptions requestOptions = ModelOptionsUtils.merge(runtimeOptions, this.defaultOptions,
            ClaudeCodeChatOptions.class);

        // Merge @JsonIgnore-annotated options explicitly since they are ignored by
        // Jackson, used by ModelOptionsUtils.
        if (runtimeOptions != null) {
            requestOptions.setInternalToolExecutionEnabled(
                ModelOptionsUtils.mergeOption(runtimeOptions.getInternalToolExecutionEnabled(),
                    this.defaultOptions.getInternalToolExecutionEnabled()));
            requestOptions.setToolNames(ToolCallingChatOptions.mergeToolNames(runtimeOptions.getToolNames(),
                this.defaultOptions.getToolNames()));
            requestOptions.setToolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(runtimeOptions.getToolCallbacks(),
                this.defaultOptions.getToolCallbacks()));
            requestOptions.setToolContext(ToolCallingChatOptions.mergeToolContext(runtimeOptions.getToolContext(),
                this.defaultOptions.getToolContext()));
        }
        else {
            requestOptions.setInternalToolExecutionEnabled(this.defaultOptions.getInternalToolExecutionEnabled());
            requestOptions.setToolNames(this.defaultOptions.getToolNames());
            requestOptions.setToolCallbacks(this.defaultOptions.getToolCallbacks());
            requestOptions.setToolContext(this.defaultOptions.getToolContext());
        }

        ToolCallingChatOptions.validateToolCallbacks(requestOptions.getToolCallbacks());

        return new Prompt(prompt.getInstructions(), requestOptions);
    }

    private String extractSystemPrompt(Prompt prompt) {
        return prompt.getInstructions().stream()
            .filter(m -> m.getMessageType() == MessageType.SYSTEM)
            .map(Message::getText)
            .collect(Collectors.joining(System.lineSeparator()));
    }

    private List<AnthropicApi.AnthropicMessage> buildAnthropicMessages(Prompt prompt) {
        List<AnthropicApi.AnthropicMessage> result = new ArrayList<>();

        for (Message message : prompt.getInstructions()) {
            MessageType messageType = message.getMessageType();

            if (messageType == MessageType.SYSTEM) {
                continue;
            }

            if (messageType == MessageType.USER) {
                List<AnthropicApi.ContentBlock> contentBlocks = new ArrayList<>();
                contentBlocks.add(new AnthropicApi.ContentBlock(message.getText()));

                if (message instanceof UserMessage userMessage) {
                    if (!CollectionUtils.isEmpty(userMessage.getMedia())) {
                        logger.warn("Claude Code CLI does not support media attachments. Ignoring media.");
                    }
                }

                result.add(new AnthropicApi.AnthropicMessage(contentBlocks, AnthropicApi.Role.USER));

            } else if (messageType == MessageType.ASSISTANT) {
                AssistantMessage assistantMessage = (AssistantMessage) message;
                List<AnthropicApi.ContentBlock> contentBlocks = new ArrayList<>();

                // Check if this is a thinking block from previous turn
                // Must preserve thinking blocks with signature for Anthropic API
                Boolean isThinking = (Boolean) assistantMessage.getMetadata().get("thinking");
                if (Boolean.TRUE.equals(isThinking)) {
                    // Create thinking content block with signature (required by Anthropic)
                    String thinking = message.getText();
                    String signature = (String) assistantMessage.getMetadata().get("signature");

                    // Use full ContentBlock constructor for thinking type
                    AnthropicApi.ContentBlock thinkingBlock = new AnthropicApi.ContentBlock(
                        AnthropicApi.ContentBlock.Type.THINKING,  // type
                        null,      // source
                        null,      // text
                        null,      // index
                        null,      // id
                        null,      // name
                        null,      // input
                        null,      // toolUseId
                        null,      // content
                        signature, // signature (required for roundtrip)
                        thinking,  // thinking
                        null,      // data
                        null,      // cacheControl
                        null,      // title
                        null,      // context
                        null       // citations
                    );

                    contentBlocks.add(thinkingBlock);
                    logger.debug("Added thinking block to conversation history (signature: {})",
                        signature != null ? "present" : "missing");
                } else {
                    // Regular assistant message with text and/or tool calls
                    StringBuilder fullContent = new StringBuilder();

                    if (StringUtils.hasText(message.getText())) {
                        fullContent.append(message.getText());
                    }

                    // Convert tool calls to text format (Claude Code CLI compatibility)
                    if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
                        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                            if (fullContent.length() > 0) {
                                fullContent.append("\n\n");
                            }
                            fullContent.append("<tool_use>\n");
                            fullContent.append("<name>").append(toolCall.name()).append("</name>\n");
                            fullContent.append("<parameters>\n");
                            fullContent.append(toolCall.arguments());
                            fullContent.append("\n</parameters>\n");
                            fullContent.append("</tool_use>");
                        }
                    }

                    if (fullContent.length() > 0) {
                        contentBlocks.add(new AnthropicApi.ContentBlock(fullContent.toString()));
                    }
                }

                result.add(new AnthropicApi.AnthropicMessage(contentBlocks, AnthropicApi.Role.ASSISTANT));

            } else if (messageType == MessageType.TOOL) {
                // Convert tool results to native Anthropic API format
                ToolResponseMessage toolMessage = (ToolResponseMessage) message;
                List<AnthropicApi.ContentBlock> userContentBlocks = new ArrayList<>();

                for (ToolResponseMessage.ToolResponse toolResponse : toolMessage.getResponses()) {
                    String responseData = toolResponse.responseData();
                    String mainText;
                    Map<String, Object> data = null;

                    // responseData can be:
                    // 1. Plain string: "Successfully read file..."
                    // 2. JSON object: {"text": "..."}
                    // 3. JSON array: [{"type": "text", "text": "..."}, {"type": "image", ...}]

                    String trimmed = responseData.trim();
                    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                        // Case 1: Plain string (no JSON)
                        mainText = responseData;
                    } else if (trimmed.startsWith("[")) {
                        // Case 2: JSON array (content blocks with possibly images)
                        try {
                            ObjectMapper objectMapper = new ObjectMapper();
                            JsonNode jsonNode = objectMapper.readTree(responseData);
                            if (jsonNode.isArray()) {
                                // Extract text from first text block
                                mainText = null;
                                for (JsonNode node : jsonNode) {
                                    if (node.has("type") && "text".equals(node.get("type").asText())) {
                                        mainText = node.get("text").asText();
                                        break;
                                    }
                                }
                                if (mainText == null) {
                                    mainText = responseData;
                                }
                            } else {
                                mainText = responseData;
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to parse JSON array response data: {}", e.getMessage());
                            mainText = responseData;
                        }
                    } else {
                        // Case 3: JSON object
                        try {
                            data = ModelOptionsUtils.jsonToMap(responseData);
                            mainText = data.get("text") != null ? data.get("text").toString() : responseData;
                        } catch (Exception e) {
                            logger.warn("Failed to parse JSON object response data: {}", e.getMessage());
                            mainText = responseData;
                        }
                    }

                    // Format as TEXT block (not tool_result) - Claude Code CLI non-native tool mode
                    // This mimics Cline's approach when nativeToolCallEnabled=false
                    String textResult = String.format("[%s] Result:\n%s", toolResponse.name(), mainText);

                    // Create TEXT block instead of TOOL_RESULT (Claude Code CLI compatibility)
                    AnthropicApi.ContentBlock textResultBlock = new AnthropicApi.ContentBlock(textResult);
                    userContentBlocks.add(textResultBlock);

                    // Add additional content blocks (images) as separate blocks in user message
                    if (data != null && data.containsKey("additionalContent")) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> additionalBlocks = (List<Map<String, Object>>) data.get("additionalContent");

                        for (Map<String, Object> block : additionalBlocks) {
                            if ("image".equals(block.get("type"))) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> source = (Map<String, Object>) block.get("source");

                                AnthropicApi.ContentBlock imageBlock = new AnthropicApi.ContentBlock(
                                    new AnthropicApi.ContentBlock.Source(
                                        source.get("media_type").toString(),
                                        source.get("data").toString()
                                    )
                                );
                                userContentBlocks.add(imageBlock);
                            }
                        }
                    }
                }

                result.add(new AnthropicApi.AnthropicMessage(userContentBlocks, AnthropicApi.Role.USER));
            } else {
                throw new IllegalArgumentException("Unsupported message type: " + messageType);
            }
        }

        return result;
    }

    private ChatResponse toChatResponse(AnthropicApi.ChatCompletionResponse apiResponse, Usage usage) {
        if (apiResponse == null) {
            logger.warn("Null chat completion returned");
            return new ChatResponse(List.of());
        }

        List<Generation> generations = new ArrayList<>();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();

        for (AnthropicApi.ContentBlock content : apiResponse.content()) {
            switch (content.type()) {
                case TEXT, TEXT_DELTA:
                    fullText.append(content.text());
                    generations.add(new Generation(
                        new AssistantMessage(content.text()),
                        ChatGenerationMetadata.builder().finishReason(apiResponse.stopReason()).build()
                    ));
                    break;

                case THINKING, THINKING_DELTA:
                    Map<String, Object> thinkingProperties = new HashMap<>();
                    thinkingProperties.put("thinking", true);
                    // Store signature for later roundtrip - required by Anthropic API
                    if (content.signature() != null) {
                        thinkingProperties.put("signature", content.signature());
                    }
                    generations.add(new Generation(
                        AssistantMessage.builder()
                            .content(content.thinking())
                            .properties(thinkingProperties)
                            .build(),
                        ChatGenerationMetadata.builder().finishReason(apiResponse.stopReason()).build()
                    ));
                    break;

                case TOOL_USE:
                    String functionCallId = content.id();
                    String functionName = content.name();
                    String functionArguments = JsonParser.toJson(content.input());
                    toolCalls.add(new AssistantMessage.ToolCall(
                        functionCallId, "function", functionName, functionArguments
                    ));
                    break;
            }
        }


        if (!CollectionUtils.isEmpty(toolCalls)) {
            AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(toolCalls)
                .build();
            generations.add(new Generation(
                assistantMessage,
                ChatGenerationMetadata.builder().finishReason(apiResponse.stopReason()).build()
            ));
        }

        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .id(apiResponse.id())
            .model(apiResponse.model())
            .usage(usage)
            .keyValue("stop-reason", apiResponse.stopReason())
            .keyValue("stop-sequence", apiResponse.stopSequence())
            .build();

        return new ChatResponse(generations, metadata);
    }

    private Usage convertUsage(AnthropicApi.Usage apiUsage) {
        int inputTokens = apiUsage.inputTokens() != null ? apiUsage.inputTokens() : 0;
        int outputTokens = apiUsage.outputTokens() != null ? apiUsage.outputTokens() : 0;
        return new DefaultUsage(inputTokens, outputTokens, inputTokens + outputTokens, apiUsage);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return this.defaultOptions.copy();
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
        private ClaudeCodeChatOptions defaultOptions = ClaudeCodeChatOptions.builder()
            .model("claude-sonnet-4-5")
            .maxTokens(8192)
            .temperature(0.7)
            .internalToolExecutionEnabled(false)
            .build();
        private ToolCallingManager toolCallingManager;
        private RetryTemplate retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE;
        private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;
        private ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate =
            new DefaultToolExecutionEligibilityPredicate();

        public Builder claudeCodeApi(ClaudeCodeApi claudeCodeApi) {
            this.claudeCodeApi = claudeCodeApi;
            return this;
        }

        public Builder defaultOptions(ClaudeCodeChatOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
            this.toolCallingManager = toolCallingManager;
            return this;
        }

        public Builder retryTemplate(RetryTemplate retryTemplate) {
            this.retryTemplate = retryTemplate;
            return this;
        }

        public Builder observationRegistry(ObservationRegistry observationRegistry) {
            this.observationRegistry = observationRegistry;
            return this;
        }

        public Builder toolExecutionEligibilityPredicate(
                ToolExecutionEligibilityPredicate predicate) {
            this.toolExecutionEligibilityPredicate = predicate;
            return this;
        }

        public ClaudeCodeChatModel build() {
            Assert.notNull(claudeCodeApi, "claudeCodeApi cannot be null");
            Assert.notNull(toolCallingManager, "toolCallingManager cannot be null");

            return new ClaudeCodeChatModel(
                claudeCodeApi,
                defaultOptions,
                toolCallingManager,
                retryTemplate,
                observationRegistry,
                toolExecutionEligibilityPredicate
            );
        }
    }
}
