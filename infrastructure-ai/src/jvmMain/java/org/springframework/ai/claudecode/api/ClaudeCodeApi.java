package org.springframework.ai.claudecode.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ClaudeCodeApi {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeCodeApi.class);

    private static final List<String> DISABLED_TOOLS = List.of(
        "Task", "Bash", "Glob", "Grep", "LS", "exit_plan_mode",
        "Read", "Edit", "MultiEdit", "Write",
        "NotebookRead", "NotebookEdit",
        "WebFetch", "TodoRead", "TodoWrite", "WebSearch"
    );

    private static final int PROCESS_TIMEOUT_SECONDS = 600;
    private static final int MAX_SYSTEM_PROMPT_LENGTH = 65536;

    private final String cliPath;
    private final String workingDirectory;
    private final boolean devMode;
    private final String mcpConfigPath;
    private final ObjectMapper objectMapper;

    private ClaudeCodeApi(String cliPath, String workingDirectory, boolean devMode, String mcpConfigPath) {
        this.cliPath = cliPath;
        this.workingDirectory = workingDirectory;
        this.devMode = devMode;
        this.mcpConfigPath = mcpConfigPath;
        this.objectMapper = new ObjectMapper();
    }

    public Flux<AnthropicApi.ChatCompletionResponse> chatCompletionStream(
            String systemPrompt,
            List<AnthropicApi.AnthropicMessage> messages,
            String model,
            Integer maxTokens,
            Double temperature,
            Integer thinkingBudgetTokens,
            List<org.springframework.ai.tool.ToolCallback> toolCallbacks) {

        return Flux.<AnthropicApi.ChatCompletionResponse>create(sink -> {
            Process process = null;
            File tempFile = null;
            long requestTimestamp = System.currentTimeMillis();

            try {
                // Append tool descriptions and conversation format note to system prompt
                String toolDescriptions = buildToolDescriptions(toolCallbacks);
                String conversationFormatNote = "\n\n# Conversation Format\n\n" +
                    "You will receive all conversation history as messages from me. Look at XML tags to understand who is author: " +
                    "`<user>content</user>` for user messages " +
                    "and `<assistant>content</assistant>` for your previous responses. Treat it as your own previous responses in the conversation.";
                String enhancedSystemPrompt = systemPrompt + toolDescriptions + conversationFormatNote;

                if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                    logger.debug("Added {} tool descriptions to system prompt ({} chars)",
                        toolCallbacks.size(), toolDescriptions.length());
                }

                boolean useFile = enhancedSystemPrompt.length() > MAX_SYSTEM_PROMPT_LENGTH ||
                                 System.getProperty("os.name").toLowerCase().contains("win");

                // Build Claude command arguments
                List<String> claudeArgs = new ArrayList<>();
                claudeArgs.add(cliPath);

                if (useFile) {
                    tempFile = createTempSystemPromptFile(enhancedSystemPrompt);
                    claudeArgs.add("--system-prompt-file");
                    claudeArgs.add(quoteShellArg(tempFile.getAbsolutePath()));
                } else {
                    claudeArgs.add("--system-prompt");
                    claudeArgs.add(quoteShellArg(enhancedSystemPrompt));
                }

                claudeArgs.add("--verbose");
                claudeArgs.add("--output-format");
                claudeArgs.add("stream-json");
                claudeArgs.add("--disallowedTools");
                claudeArgs.add(String.join(",", DISABLED_TOOLS));
                claudeArgs.add("--max-turns");
                claudeArgs.add("1");
                claudeArgs.add("--model");
                claudeArgs.add(model);
                claudeArgs.add("-p");
                
                // Load MCP config if provided, otherwise disable all settings
                if (mcpConfigPath != null && !mcpConfigPath.isEmpty()) {
                    logger.debug("Loading MCP config from: {}", mcpConfigPath);
                    claudeArgs.add("--mcp-config");
                    claudeArgs.add(quoteShellArg(mcpConfigPath));
                }
                
                // Disable CLAUDE.md and other settings loading
                // Empty string means don't load any settings (user, project, local)
                logger.debug("No MCP config provided, disabling all settings");
                claudeArgs.add("--setting-sources");
                claudeArgs.add("\"\"");

                // Join arguments into single command string
                String claudeCommand = String.join(" ", claudeArgs);

                // Detect shell and wrap command for proper environment initialization
                String shell = detectShell();
                logger.debug("Using shell: {}", shell);

                List<String> command = List.of(shell, "-l", "-c", claudeCommand);

                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.directory(new File(workingDirectory));

                Map<String, String> env = processBuilder.environment();
                env.put("CLAUDE_CODE_MAX_OUTPUT_TOKENS", String.valueOf(maxTokens != null ? maxTokens : 32000));
                env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
                env.put("DISABLE_NON_ESSENTIAL_MODEL_CALLS", "1");
                env.put("MAX_THINKING_TOKENS", String.valueOf(thinkingBudgetTokens != null ? thinkingBudgetTokens : 0));
                env.remove("ANTHROPIC_API_KEY");

                logger.debug("Starting Claude Code process: {}", String.join(" ", command));
                process = processBuilder.start();

                Process finalProcess = process;

                writeMessagesToStdin(process, messages, requestTimestamp);

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8)
                );

                StringBuilder stderrBuilder = new StringBuilder();
                Thread stderrThread = startStderrReader(finalProcess, stderrBuilder);

                StringBuilder responseBuilder = new StringBuilder();
                String line;
                Double totalCost = null;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line).append("\n");
                    if (line.trim().isEmpty()) {
                        continue;
                    }

                    try {
                        // Log raw JSONL event for debugging streaming structure (dev mode only)
                        if (devMode) {
                            logger.debug("CLAUDE_RAW_EVENT: {}", line);
                        }

                        ClaudeCodeStreamEvent event = objectMapper.readValue(line, ClaudeCodeStreamEvent.class);
                        logger.debug("Received event type: {}", event.type());

                        if (event instanceof ClaudeCodeStreamEvent.InitEvent initEvent) {
                            logger.debug("Init event - subscription: {}", initEvent.isSubscription());
                            continue;
                        }

                        if (event instanceof ClaudeCodeStreamEvent.AssistantEvent assistantEvent) {
                            // Check for API errors in assistant messages (fail fast)
                            // Format: "API Error: <<status code>> <<json>>"
                            var message = assistantEvent.message();
                            if (message.stopReason() != null && !message.content().isEmpty()) {
                                var firstBlock = message.content().get(0);
                                if (firstBlock.text() != null && firstBlock.text().startsWith("API Error")) {
                                    String errorText = firstBlock.text();
                                    logger.error("Claude Code API Error: {}", errorText);
                                    sink.error(new RuntimeException("Claude Code API Error: " + errorText));
                                    return;
                                }
                            }

                            // Log token usage including cache stats
                            if (message.usage() != null) {
                                var usage = message.usage();
                                logger.info("TOKEN USAGE: input={}, output={}, cache_creation={}, cache_read={}, model={}",
                                    usage.inputTokens(),
                                    usage.outputTokens(),
                                    usage.cacheCreationInputTokens() != null ? usage.cacheCreationInputTokens() : 0,
                                    usage.cacheReadInputTokens() != null ? usage.cacheReadInputTokens() : 0,
                                    message.model());
                            }

                            // Emit each complete block immediately (thinking, text, tool_use)
                            // toChatResponse() will parse tool calls and clean XML automatically
                            AnthropicApi.ChatCompletionResponse response = convertAssistantEvent(assistantEvent);
                            sink.next(response);
                        }

                        if (event instanceof ClaudeCodeStreamEvent.ErrorEvent errorEvent) {
                            sink.error(new RuntimeException("Claude Code error: " + errorEvent.message()));
                            return;
                        }

                        if (event instanceof ClaudeCodeStreamEvent.ResultEvent resultEvent) {
                            totalCost = resultEvent.totalCostUsd();
                            logger.debug("Result event - cost: ${}, duration: {}ms",
                                totalCost, resultEvent.durationMs());
                        }

                    } catch (Exception e) {
                        logger.error("Failed to parse JSONL line: {}", line, e);
                    }
                }

                boolean finished = finalProcess.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    finalProcess.destroyForcibly();
                    sink.error(new RuntimeException("Claude Code process timed out"));
                    return;
                }

                stderrThread.join(1000);

                int exitCode = finalProcess.exitValue();

                // Save response dump
                saveResponseDump(requestTimestamp, responseBuilder.toString());

                if (exitCode != 0) {
                    String stderr = stderrBuilder.toString();
                    logger.error("Claude Code process exited with code {}: {}", exitCode, stderr);
                    sink.error(new RuntimeException(
                        String.format("Claude Code process failed with exit code %d: %s", exitCode, stderr)
                    ));
                    return;
                }

                sink.complete();

            } catch (Exception e) {
                logger.error("Error during Claude Code execution", e);
                sink.error(e);
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile.toPath());
                    } catch (IOException e) {
                        logger.warn("Failed to delete temp file: {}", tempFile, e);
                    }
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private File createTempSystemPromptFile(String systemPrompt) throws IOException {
        File tempFile = File.createTempFile("claude-system-prompt-", ".txt");
        Files.writeString(tempFile.toPath(), systemPrompt, StandardCharsets.UTF_8);
        return tempFile;
    }

    private String buildToolDescriptions(List<org.springframework.ai.tool.ToolCallback> toolCallbacks) {
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            return "";
        }

        StringBuilder toolsSection = new StringBuilder();
        toolsSection.append("\n\n# Available Tools\n\n");
        toolsSection.append("You have access to the following tools.\n\n");

        for (org.springframework.ai.tool.ToolCallback toolCallback : toolCallbacks) {
            var definition = toolCallback.getToolDefinition();

            toolsSection.append("## ").append(definition.name()).append("\n\n");
            toolsSection.append(definition.description()).append("\n\n");

            // Add parameter schema
            String inputSchemaJson = definition.inputSchema();
            if (inputSchemaJson != null && !inputSchemaJson.isBlank()) {
                toolsSection.append("**Parameters (JSON Schema):**\n");
                toolsSection.append("```json\n");
                toolsSection.append(inputSchemaJson);
                toolsSection.append("\n```\n\n");
            }
        }

        return toolsSection.toString();
    }

    private void writeMessagesToStdin(Process process, List<AnthropicApi.AnthropicMessage> messages, long requestTimestamp) throws IOException {
        List<String> plainTextMessages = messages.stream()
            .map(this::convertMessageToPlainText)
            .collect(Collectors.toList());

        // Build plain text for stdin (just content strings, no JSON wrapping)
        StringBuilder inputBuilder = new StringBuilder();
        for (String content : plainTextMessages) {
            inputBuilder.append(content).append("\n");
        }
        String input = inputBuilder.toString();

        // Build dump content (just plain text for now)
        String dumpContent = input;

        // Log history size
        logger.info("HISTORY: {} messages, {} characters", plainTextMessages.size(), input.length());

        // Save request dump
        Path dumpFile = Paths.get("logs/dumps/" + requestTimestamp + "-request.txt");
        Files.createDirectories(dumpFile.getParent());
        Files.writeString(dumpFile, dumpContent);
        logger.debug("Saved request dump to: {}", dumpFile.toAbsolutePath());

        // Log preview
        logger.debug("Full input: {}", input);

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(input);
            writer.flush();
        }
    }

    private String convertMessageToPlainText(AnthropicApi.AnthropicMessage message) {
        String originalRole = message.role().name().toLowerCase();

        StringBuilder contentBuilder = new StringBuilder();
        for (AnthropicApi.ContentBlock block : message.content()) {
            String wrappedContent = wrapContentBlock(block, originalRole);
            contentBuilder.append(wrappedContent);
        }

        return contentBuilder.toString();
    }

    private String wrapContentBlock(AnthropicApi.ContentBlock block, String role) {
        String xmlTag = role.equals("assistant") ? "assistant" : "user";

        if (block.type() == AnthropicApi.ContentBlock.Type.TEXT) {
            return "<" + xmlTag + ">" + block.text() + "</" + xmlTag + ">";
        }

        if (block.type() == AnthropicApi.ContentBlock.Type.THINKING) {
            return "<thinking role=\"" + xmlTag + "\">" + block.thinking() + "</thinking>";
        }

        if (block.type() == AnthropicApi.ContentBlock.Type.TOOL_USE) {
            StringBuilder toolText = new StringBuilder();
            toolText.append("<tool_use role=\"").append(xmlTag).append("\" name=\"").append(block.name()).append("\" id=\"").append(block.id()).append("\">");
            try {
                toolText.append("\n").append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(block.input()));
            } catch (Exception e) {
                toolText.append(block.input());
            }
            toolText.append("\n</tool_use>");
            return toolText.toString();
        }

        if (block.type() == AnthropicApi.ContentBlock.Type.TOOL_RESULT) {
            StringBuilder resultText = new StringBuilder();
            resultText.append("<tool_result role=\"").append(xmlTag).append("\" tool_use_id=\"").append(block.toolUseId()).append("\">");

            if (StringUtils.hasText(block.content())) {
                try {
                    Object parsedContent = objectMapper.readValue(block.content(), Object.class);
                    resultText.append("\n").append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsedContent));
                } catch (Exception e) {
                    resultText.append(block.content());
                }
            }
            resultText.append("\n</tool_result>");
            return resultText.toString();
        }

        // Fallback
        return "<" + xmlTag + " block_type=\"" + block.type().name().toLowerCase() + "\">" +
               (StringUtils.hasText(block.text()) ? block.text() : "") +
               "</" + xmlTag + ">";
    }

    private Thread startStderrReader(Process process, StringBuilder stderrBuilder) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrBuilder.append(line).append("\n");
                    logger.debug("stderr: {}", line);
                }
            } catch (IOException e) {
                logger.debug("Error reading stderr", e);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private AnthropicApi.ChatCompletionResponse convertAssistantEvent(
            ClaudeCodeStreamEvent.AssistantEvent event) {

        ClaudeCodeStreamEvent.AssistantEvent.AnthropicMessage msg = event.message();

        List<AnthropicApi.ContentBlock> contentBlocks = msg.content().stream()
            .map(this::convertContentBlock)
            .collect(Collectors.toList());

        AnthropicApi.Usage usage = null;
        if (msg.usage() != null) {
            ClaudeCodeStreamEvent.AssistantEvent.AnthropicMessage.Usage u = msg.usage();
            usage = new AnthropicApi.Usage(
                u.inputTokens(),
                u.outputTokens(),
                u.cacheCreationInputTokens(),
                u.cacheReadInputTokens()
            );
        }

        return new AnthropicApi.ChatCompletionResponse(
            msg.id(),
            msg.type(),
            AnthropicApi.Role.valueOf(msg.role().toUpperCase()),
            contentBlocks,
            msg.model(),
            msg.stopReason(),
            msg.stopSequence(),
            usage
        );
    }

    private AnthropicApi.ContentBlock convertContentBlock(
            ClaudeCodeStreamEvent.AssistantEvent.AnthropicMessage.ContentBlock block) {

        String typeStr = block.type();
        if (typeStr == null) {
            typeStr = "text";
        }

        return switch (typeStr) {
            case "text" -> new AnthropicApi.ContentBlock(block.text());
            case "thinking" -> AnthropicApi.ContentBlock.from(new AnthropicApi.ContentBlock(""))
                .type(AnthropicApi.ContentBlock.Type.THINKING)
                .thinking(block.thinking())
                .build();
            case "tool_use" -> new AnthropicApi.ContentBlock(
                AnthropicApi.ContentBlock.Type.TOOL_USE,
                block.id(),
                block.name(),
                block.input()
            );
            default -> new AnthropicApi.ContentBlock(block.text() != null ? block.text() : "");
        };
    }

    private void saveResponseDump(long requestTimestamp, String response) {
        try {
            // Sanitize and pretty-print response
            StringBuilder sanitizedResponse = new StringBuilder();
            for (String line : response.split("\n")) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    Map<String, Object> event = objectMapper.readValue(line, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    Map<String, Object> sanitizedEvent = sanitizeEventForDump(event);
                    String prettyLine = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sanitizedEvent);
                    sanitizedResponse.append(prettyLine).append("\n");
                } catch (Exception e) {
                    // If parsing fails, keep original line
                    sanitizedResponse.append(line).append("\n");
                }
            }

            Path dumpFile = Paths.get("logs/dumps/" + requestTimestamp + "-response.jsonl");
            Files.createDirectories(dumpFile.getParent());
            Files.writeString(dumpFile, sanitizedResponse.toString());
            logger.debug("Saved response dump to: {}", dumpFile.toAbsolutePath());
        } catch (Exception e) {
            logger.warn("Failed to save response dump: {}", e.getMessage());
        }
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeEventForDump(Map<String, Object> event) {
        Map<String, Object> sanitized = new HashMap<>(event);

        // Sanitize assistant message content
        if (sanitized.containsKey("message")) {
            Map<String, Object> message = (Map<String, Object>) sanitized.get("message");
            Map<String, Object> sanitizedMessage = new HashMap<>(message);

            if (sanitizedMessage.containsKey("content")) {
                List<Map<String, Object>> content = (List<Map<String, Object>>) sanitizedMessage.get("content");
                List<Map<String, Object>> sanitizedContent = new ArrayList<>();

                for (Map<String, Object> block : content) {
                    Map<String, Object> sanitizedBlock = new HashMap<>(block);

                    if (sanitizedBlock.containsKey("text") && sanitizedBlock.get("text") instanceof String) {
                        String text = (String) sanitizedBlock.get("text");
                        sanitizedBlock.put("text", truncateWithLength(text, 100));
                    }

                    if (sanitizedBlock.containsKey("thinking") && sanitizedBlock.get("thinking") instanceof String) {
                        String thinking = (String) sanitizedBlock.get("thinking");
                        sanitizedBlock.put("thinking", truncateWithLength(thinking, 100));
                    }

                    sanitizedContent.add(sanitizedBlock);
                }

                sanitizedMessage.put("content", sanitizedContent);
            }

            sanitized.put("message", sanitizedMessage);
        }

        // Sanitize result event
        if (sanitized.containsKey("result") && sanitized.get("result") instanceof String) {
            String result = (String) sanitized.get("result");
            sanitized.put("result", truncateWithLength(result, 100));
        }

        return sanitized;
    }

    /**
     * Truncates text to maxLength characters and appends "... REMOVED(totalLength)".
     */
    private String truncateWithLength(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "... REMOVED(" + text.length() + ")";
    }

    /**
     * Detects the user's shell with fallback chain for proper environment initialization.
     * Prioritizes SHELL env variable, then checks for common shells.
     */
    private static String detectShell() {
        // Strategy 1: Use SHELL environment variable
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isEmpty() && new File(shell).exists()) {
            logger.debug("Detected shell from SHELL env: {}", shell);
            return shell;
        }

        // Strategy 2: Check common shell paths
        String[] commonShells = {
            "/bin/zsh",
            "/bin/bash",
            "/bin/fish",
            "/bin/sh"
        };

        for (String shellPath : commonShells) {
            if (new File(shellPath).exists()) {
                logger.debug("Using shell: {}", shellPath);
                return shellPath;
            }
        }

        // Fallback to sh (POSIX required)
        logger.warn("No shell detected, falling back to /bin/sh");
        return "/bin/sh";
    }

    /**
     * Quotes shell argument for safe execution in shell command.
     * Escapes single quotes and wraps in single quotes for POSIX shells.
     */
    private static String quoteShellArg(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "''";
        }

        // Escape single quotes by replacing ' with '\''
        String escaped = arg.replace("'", "'\\''");
        return "'" + escaped + "'";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String cliPath = "claude";
        private String workingDirectory = System.getProperty("user.dir");
        private boolean devMode = false;
        private String mcpConfigPath = null;

        public Builder cliPath(String cliPath) {
            this.cliPath = cliPath;
            return this;
        }

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder devMode(boolean devMode) {
            this.devMode = devMode;
            return this;
        }

        public Builder mcpConfigPath(String mcpConfigPath) {
            this.mcpConfigPath = mcpConfigPath;
            return this;
        }

        public ClaudeCodeApi build() {
            Assert.hasText(cliPath, "CLI path must not be empty");
            Assert.hasText(workingDirectory, "Working directory must not be empty");
            return new ClaudeCodeApi(cliPath, workingDirectory, devMode, mcpConfigPath);
        }
    }
}
