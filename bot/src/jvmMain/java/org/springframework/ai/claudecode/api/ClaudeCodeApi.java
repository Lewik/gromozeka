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
import java.util.UUID;
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
    private final ObjectMapper objectMapper;

    private ClaudeCodeApi(String cliPath, String workingDirectory, boolean devMode) {
        this.cliPath = cliPath;
        this.workingDirectory = workingDirectory;
        this.devMode = devMode;
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

            try {
                // Append tool descriptions to system prompt
                String toolDescriptions = buildToolDescriptions(toolCallbacks);
                String enhancedSystemPrompt = systemPrompt + toolDescriptions;

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
                File finalTempFile = tempFile;

                writeMessagesToStdin(process, messages);

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8)
                );

                StringBuilder stderrBuilder = new StringBuilder();
                Thread stderrThread = startStderrReader(finalProcess, stderrBuilder);

                String line;
                Double totalCost = null;
                while ((line = reader.readLine()) != null) {
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
        toolsSection.append("You have access to the following tools. Call them using standard Anthropic tool_use format (native JSON blocks).\n\n");

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

    private void writeMessagesToStdin(Process process, List<AnthropicApi.AnthropicMessage> messages) throws IOException {
        List<Map<String, Object>> messageList = messages.stream()
            .map(this::convertMessageToMap)
            .collect(Collectors.toList());

        String json = objectMapper.writeValueAsString(messageList);
        logger.debug("Writing messages to stdin: {} characters", json.length());

        // Debug logging: save JSON to file for inspection
        try {
            Path debugFile = Paths.get("logs/last-stdin.json");
            Files.createDirectories(debugFile.getParent());
            Files.writeString(debugFile, json);
            logger.debug("Saved stdin JSON to: {}", debugFile.toAbsolutePath());
        } catch (Exception e) {
            logger.warn("Failed to save debug JSON: {}", e.getMessage());
        }

        // Log message structure
        String structure = messageList.stream()
            .map(m -> {
                String role = (String) m.get("role");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> content = (List<Map<String, Object>>) m.get("content");
                String contentTypes = content.stream()
                    .map(c -> (String) c.get("type"))
                    .collect(Collectors.joining(","));
                return role + "[" + contentTypes + "]";
            })
            .collect(Collectors.joining(" -> "));
        logger.debug("Message structure: {}", structure);

        // Validate JSON
        try {
            objectMapper.readTree(json);
            logger.debug("JSON validation: OK");
        } catch (Exception e) {
            logger.error("JSON validation FAILED: {}", e.getMessage());
        }

        // Log preview
        if (json.length() > 1000) {
            logger.debug("JSON preview (first 500 chars): {}", json.substring(0, 500));
            logger.debug("JSON preview (last 500 chars): {}", json.substring(json.length() - 500));
        } else {
            logger.debug("Full JSON: {}", json);
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(json);
            writer.flush();
        }
    }

    private Map<String, Object> convertMessageToMap(AnthropicApi.AnthropicMessage message) {
        Map<String, Object> map = new HashMap<>();
        map.put("role", message.role().name().toLowerCase());

        List<Map<String, Object>> contentList = message.content().stream()
            .map(this::convertContentBlockToMap)
            .collect(Collectors.toList());
        map.put("content", contentList);

        return map;
    }

    private Map<String, Object> convertContentBlockToMap(AnthropicApi.ContentBlock block) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", block.type().name().toLowerCase());

        // Handle TOOL_RESULT with structured content (images, etc)
        if (block.type() == AnthropicApi.ContentBlock.Type.TOOL_RESULT) {
            map.put("tool_use_id", block.toolUseId());

            if (StringUtils.hasText(block.content())) {
                try {
                    // Parse JSON string into structured content array
                    Object parsedContent = objectMapper.readValue(block.content(), Object.class);
                    map.put("content", parsedContent);
                } catch (Exception e) {
                    // Fallback - if not JSON, use as plain text
                    logger.warn("Failed to parse tool_result content as JSON, using as text: {}", e.getMessage());
                    map.put("content", block.content());
                }
            }
            return map;
        }

        // Handle IMAGE blocks with source
        if (block.source() != null) {
            Map<String, Object> source = new HashMap<>();
            if (StringUtils.hasText(block.source().type())) {
                source.put("type", block.source().type());
            }
            if (StringUtils.hasText(block.source().mediaType())) {
                source.put("media_type", block.source().mediaType());
            }
            if (StringUtils.hasText(block.source().data())) {
                source.put("data", block.source().data());
            }
            if (StringUtils.hasText(block.source().url())) {
                source.put("url", block.source().url());
            }
            map.put("source", source);
        }

        if (StringUtils.hasText(block.text())) {
            map.put("text", block.text());
        }
        if (block.input() != null) {
            map.put("input", block.input());
        }
        if (StringUtils.hasText(block.id())) {
            map.put("id", block.id());
        }
        if (StringUtils.hasText(block.name())) {
            map.put("name", block.name());
        }
        // Serialize thinking and signature for Anthropic extended thinking
        if (StringUtils.hasText(block.thinking())) {
            map.put("thinking", block.thinking());
        }
        if (StringUtils.hasText(block.signature())) {
            map.put("signature", block.signature());
        }

        return map;
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

        public ClaudeCodeApi build() {
            Assert.hasText(cliPath, "CLI path must not be empty");
            Assert.hasText(workingDirectory, "Working directory must not be empty");
            return new ClaudeCodeApi(cliPath, workingDirectory, devMode);
        }
    }
}
