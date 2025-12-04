package org.springframework.ai.anthropic;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionRequest;
import org.springframework.ai.anthropic.api.AnthropicApi.ContentBlock;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Enhanced version of AnthropicChatModel that sends system prompts as an array of ContentBlocks.
 */
public class EnhancedAnthropicChatModel extends AnthropicChatModel {

    public EnhancedAnthropicChatModel(
            AnthropicApi anthropicApi,
            AnthropicChatOptions options,
            ToolCallingManager toolCallingManager,
            RetryTemplate retryTemplate,
            ObservationRegistry observationRegistry
    ) {
        super(anthropicApi, options, toolCallingManager, retryTemplate, observationRegistry);
    }

    @Override
    ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {
        List<Message> systemMessages = prompt.getInstructions()
                .stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM)
                .toList();

        List<ContentBlock> systemBlocks = new ArrayList<>();

        for (Message msg : systemMessages) {
            String text = msg.getText();
            if (StringUtils.hasText(text)) {
                systemBlocks.add(new ContentBlock(text));
            }
        }

        Prompt promptWithoutSystem = new Prompt(
                prompt.getInstructions().stream()
                        .filter(m -> m.getMessageType() != MessageType.SYSTEM)
                        .collect(Collectors.toList()),
                prompt.getOptions()
        );

        ChatCompletionRequest baseRequest = super.createRequest(promptWithoutSystem, stream);

        return ChatCompletionRequest.from(baseRequest)
                .system(systemBlocks.isEmpty() ? null : systemBlocks)
                .build();
    }
}
