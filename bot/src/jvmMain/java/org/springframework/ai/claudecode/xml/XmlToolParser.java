package org.springframework.ai.claudecode.xml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.ModelOptionsUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses XML-formatted tool calls from Claude assistant messages.
 *
 * <p>Extracts tool calls from XML tags embedded in assistant text and converts
 * them to Spring AI ToolCall objects for execution via ToolCallingManager.</p>
 *
 * @see SystemPromptEnhancer
 */
public class XmlToolParser {

    private static final Logger logger = LoggerFactory.getLogger(XmlToolParser.class);

    private final Set<String> registeredToolNames;

    public XmlToolParser(Set<String> registeredToolNames) {
        this.registeredToolNames = registeredToolNames != null ? registeredToolNames : Collections.emptySet();
    }

    public static class ParsedMessage {
        private final String textContent;
        private final List<AssistantMessage.ToolCall> toolCalls;

        public ParsedMessage(String textContent, List<AssistantMessage.ToolCall> toolCalls) {
            this.textContent = textContent;
            this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
        }

        public String getTextContent() {
            return textContent;
        }

        public List<AssistantMessage.ToolCall> getToolCalls() {
            return toolCalls;
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    public ParsedMessage parse(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.isEmpty()) {
            return new ParsedMessage("", Collections.emptyList());
        }

        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        StringBuilder textBuffer = new StringBuilder();

        Pattern toolPattern = Pattern.compile(
            "<(\\w+)>\\s*(.+?)\\s*</\\1>",
            Pattern.DOTALL
        );

        Matcher matcher = toolPattern.matcher(assistantMessage);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                textBuffer.append(assistantMessage, lastEnd, matcher.start());
            }

            String toolName = matcher.group(1);
            String toolContent = matcher.group(2);

            if (isKnownTool(toolName)) {
                logger.debug("Found tool call: {} with content length: {}", toolName, toolContent.length());

                Map<String, Object> arguments = parseToolArguments(toolContent);

                AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    UUID.randomUUID().toString(),
                    "function",
                    toolName,
                    convertArgumentsToJson(arguments)
                );

                toolCalls.add(toolCall);
            } else {
                textBuffer.append(matcher.group(0));
            }

            lastEnd = matcher.end();
        }

        if (lastEnd < assistantMessage.length()) {
            textBuffer.append(assistantMessage.substring(lastEnd));
        }

        String finalText = textBuffer.toString().trim();
        logger.debug("Parsed message: {} tool calls, text length: {}", toolCalls.size(), finalText.length());

        return new ParsedMessage(finalText, toolCalls);
    }

    private Map<String, Object> parseToolArguments(String toolContent) {
        Map<String, Object> arguments = new LinkedHashMap<>();

        Pattern paramPattern = Pattern.compile(
            "<(\\w+)>\\s*(.+?)\\s*</\\1>",
            Pattern.DOTALL
        );

        Matcher matcher = paramPattern.matcher(toolContent);
        while (matcher.find()) {
            String paramName = matcher.group(1);
            String paramValue = matcher.group(2).trim();
            arguments.put(paramName, inferType(paramValue));
        }

        return arguments;
    }

    private String convertArgumentsToJson(Map<String, Object> arguments) {
        return ModelOptionsUtils.toJsonString(arguments);
    }

    private Object inferType(String value) {
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.parseBoolean(value);
        }

        if (value.matches("-?\\d+")) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                try {
                    return Long.parseLong(value);
                } catch (NumberFormatException e2) {
                    // Fall through to string
                }
            }
        }

        if (value.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                // Fall through to string
            }
        }

        return value;
    }

    private boolean isKnownTool(String name) {
        boolean isKnown = registeredToolNames.contains(name);
        if (!isKnown) {
            logger.trace("Unknown tool name: {}, registered tools: {}", name, registeredToolNames);
        }
        return isKnown;
    }
}
