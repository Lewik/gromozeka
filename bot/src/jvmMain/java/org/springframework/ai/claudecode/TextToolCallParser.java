package org.springframework.ai.claudecode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextToolCallParser {

    private static final Logger logger = LoggerFactory.getLogger(TextToolCallParser.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern TOOL_USE_PATTERN = Pattern.compile(
        "<tool_use>\\s*<name>([^<]+)</name>\\s*<parameters>\\s*([\\s\\S]*?)\\s*</parameters>\\s*</tool_use>",
        Pattern.CASE_INSENSITIVE
    );

    public static class ParsedToolCall {
        private final String id;
        private final String name;
        private final String arguments;

        public ParsedToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getArguments() {
            return arguments;
        }
    }

    public static List<ParsedToolCall> parseToolCalls(String text) {
        List<ParsedToolCall> toolCalls = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return toolCalls;
        }

        Matcher matcher = TOOL_USE_PATTERN.matcher(text);

        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String parametersJson = matcher.group(2).trim();

            String toolCallId = "toolu_" + UUID.randomUUID().toString().substring(0, 24);

            String normalizedJson = normalizeJson(parametersJson);

            logger.debug("Parsed tool call: name={}, parameters={}", name, normalizedJson);

            toolCalls.add(new ParsedToolCall(toolCallId, name, normalizedJson));
        }

        return toolCalls;
    }

    private static String normalizeJson(String json) {
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return objectMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            logger.warn("Failed to normalize JSON, using as-is: {}", json, e);
            return json;
        }
    }

    public static String removeToolCallsFromText(String text) {
        if (text == null) {
            return null;
        }

        return TOOL_USE_PATTERN.matcher(text).replaceAll("").trim();
    }
}
