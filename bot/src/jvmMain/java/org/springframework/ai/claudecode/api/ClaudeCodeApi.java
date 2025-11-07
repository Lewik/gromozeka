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
    private final ObjectMapper objectMapper;

    private ClaudeCodeApi(String cliPath, String workingDirectory) {
        this.cliPath = cliPath;
        this.workingDirectory = workingDirectory;
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

                List<String> command = new ArrayList<>();
                command.add(cliPath);

                if (useFile) {
                    tempFile = createTempSystemPromptFile(enhancedSystemPrompt);
                    command.add("--system-prompt-file");
                    command.add(tempFile.getAbsolutePath());
                } else {
                    command.add("--system-prompt");
                    command.add(enhancedSystemPrompt);
                }

                command.add("--verbose");
                command.add("--output-format");
                command.add("stream-json");
                command.add("--disallowedTools");
                command.add(String.join(",", DISABLED_TOOLS));
                command.add("--max-turns");
                command.add("1");
                command.add("--model");
                command.add(model);
                command.add("-p");

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
                        ClaudeCodeStreamEvent event = objectMapper.readValue(line, ClaudeCodeStreamEvent.class);
                        logger.debug("Received event type: {}", event.type());

                        if (event instanceof ClaudeCodeStreamEvent.InitEvent initEvent) {
                            logger.debug("Init event - subscription: {}", initEvent.isSubscription());
                            continue;
                        }

                        if (event instanceof ClaudeCodeStreamEvent.AssistantEvent assistantEvent) {
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
        toolsSection.append("You have access to the following tools to help accomplish tasks:\n\n");

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

            toolsSection.append("To use this tool, respond with a tool_use block:\n");
            toolsSection.append("```xml\n");
            toolsSection.append("<tool_use>\n");
            toolsSection.append("  <name>").append(definition.name()).append("</name>\n");
            toolsSection.append("  <parameters>\n");
            toolsSection.append("    {\"param_name\": \"param_value\"}\n");
            toolsSection.append("  </parameters>\n");
            toolsSection.append("</tool_use>\n");
            toolsSection.append("```\n\n");
        }

        return toolsSection.toString();
    }

    private void writeMessagesToStdin(Process process, List<AnthropicApi.AnthropicMessage> messages) throws IOException {
        List<Map<String, Object>> messageList = messages.stream()
            .map(this::convertMessageToMap)
            .collect(Collectors.toList());

        String json = objectMapper.writeValueAsString(messageList);
        logger.debug("Writing messages to stdin: {} characters", json.length());

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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String cliPath = "claude";
        private String workingDirectory = System.getProperty("user.dir");

        public Builder cliPath(String cliPath) {
            this.cliPath = cliPath;
            return this;
        }

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public ClaudeCodeApi build() {
            Assert.hasText(cliPath, "CLI path must not be empty");
            Assert.hasText(workingDirectory, "Working directory must not be empty");
            return new ClaudeCodeApi(cliPath, workingDirectory);
        }
    }
}
