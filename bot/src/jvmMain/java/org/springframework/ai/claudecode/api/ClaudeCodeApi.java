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

package org.springframework.ai.claudecode.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.claudecode.exception.ClaudeCodeProcessException;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Low-level API for interacting with Claude Code CLI process.
 * This class handles process lifecycle, stdin/stdout communication, and JSONL parsing.
 */
public class ClaudeCodeApi {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeCodeApi.class);

    private static final String DEFAULT_CLAUDE_PATH = "claude";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    private static final String CLAUDE_CODE_MAX_OUTPUT_TOKENS = "32000";

    private final String claudeCodePath;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final boolean strictMode;

    public ClaudeCodeApi() {
        this(DEFAULT_CLAUDE_PATH, DEFAULT_TIMEOUT, false);
    }

    public ClaudeCodeApi(String claudeCodePath, Duration timeout) {
        this(claudeCodePath, timeout, false);
    }

    public ClaudeCodeApi(String claudeCodePath, Duration timeout, boolean strictMode) {
        this.claudeCodePath = claudeCodePath != null ? claudeCodePath : DEFAULT_CLAUDE_PATH;
        this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        this.strictMode = strictMode;
        this.objectMapper = createObjectMapper();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        if (strictMode) {
            // DEV/STRICT: Fail on unknown properties - forces us to update models when API changes
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
            logger.debug("ClaudeCodeApi running in STRICT mode - will fail on unknown JSON properties");
        } else {
            // PROD/LENIENT: Ignore unknown properties - graceful degradation for forward compatibility
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            logger.debug("ClaudeCodeApi running in LENIENT mode - will ignore unknown JSON properties");
        }

        return mapper;
    }

    /**
     * Execute Claude Code CLI and return streaming responses.
     * This is a fire-and-forget operation: start process, write messages to stdin, close stdin, read stdout stream.
     *
     * @param request The request containing model, messages, and options
     * @return Flux of stream responses
     */
    public Flux<ClaudeCodeStreamResponse> chatCompletionStream(ClaudeCodeRequest request) {
        return Flux.create(sink -> {
            Process process = null;
            Thread stderrReader = null;

            try {
                process = startClaudeProcess(request);
                final Process finalProcess = process;

                // Handle cancellation - destroy process when flux is cancelled
                sink.onCancel(() -> {
                    logger.debug("Flux cancelled, destroying Claude CLI process");
                    if (finalProcess != null && finalProcess.isAlive()) {
                        finalProcess.destroyForcibly();
                        logger.debug("Claude CLI process destroyed forcibly");
                    }
                });

                // Start stderr monitoring in background
                StringBuilder stderrBuffer = new StringBuilder();
                stderrReader = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(finalProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            stderrBuffer.append(line).append("\n");
                            logger.debug("Claude CLI stderr: {}", line);
                        }
                    } catch (IOException e) {
                        logger.warn("Error reading stderr", e);
                    }
                }, "claude-stderr-reader");
                stderrReader.setDaemon(true);
                stderrReader.start();

                // Write messages to stdin and close it (fire-and-forget)
                writeMessagesToStdin(process, request.messages());

                // Read stdout stream line by line
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                    logger.debug("Starting to read Claude CLI stdout stream");
                    String line;
                    int lineCount = 0;
                    while ((line = reader.readLine()) != null) {
                        lineCount++;
                        logger.debug("Read line #{} from stdout", lineCount);

                        if (line.trim().isEmpty()) {
                            logger.debug("Skipping empty line");
                            continue;
                        }

                        try {
                            // Log raw JSONL line for debugging
                            logger.debug("Claude CLI stream line: {}", line.length() > 500 ?
                                line.substring(0, 500) + "... (truncated)" : line);

                            ClaudeCodeStreamResponse response = parseStreamLine(line);

                            // Log response type for debugging
                            if (response instanceof ClaudeCodeStreamResponse.AssistantMessage assistantMsg) {
                                logger.debug("AssistantMessage: {} content blocks, stop_reason: {}",
                                    assistantMsg.message().content().size(),
                                    assistantMsg.message().stopReason());

                                // Log but DON'T close stream - maybe more messages are coming
                                if (assistantMsg.message().stopReason() == null) {
                                    logger.debug("AssistantMessage with null stop_reason - continuing to read stream");
                                }
                            } else {
                                logger.debug("Stream response type: {}", response.type());
                            }

                            sink.next(response);

                            // Check for completion or error responses
                            if (response instanceof ClaudeCodeStreamResponse.Result result) {
                                // Result signals completion - close stream
                                logger.debug("Received result, completing stream: num_turns={}, is_error={}",
                                    result.numTurns(), result.isError());
                                sink.complete();
                                logger.debug("Stream completed, returning from reader thread");
                                return;
                            }

                            if (response instanceof ClaudeCodeStreamResponse.Error error) {
                                sink.error(new ClaudeCodeProcessException(
                                    "Claude CLI returned error: " + error.message()));
                                return;
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to parse stream line: {}", line, e);
                            // Continue reading - partial data is acceptable
                        }
                    }

                    logger.debug("Finished reading stdout stream, read {} lines total", lineCount);
                }

                logger.debug("Stdout reader closed, waiting for process to complete");

                // Wait for process to complete
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);

                if (!finished) {
                    process.destroyForcibly();
                    sink.error(new ClaudeCodeProcessException(
                        "Claude CLI process timeout after " + timeout.toSeconds() + " seconds"));
                    return;
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    String errorOutput = stderrBuffer.toString().trim();
                    sink.error(new ClaudeCodeProcessException(
                        "Claude CLI process exited with code " + exitCode +
                        (errorOutput.isEmpty() ? "" : ": " + errorOutput)));
                    return;
                }

                sink.complete();

            } catch (Exception e) {
                logger.error("Error executing Claude CLI", e);
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                sink.error(new ClaudeCodeProcessException("Failed to execute Claude CLI", e));
            }
        });
    }

    private Process startClaudeProcess(ClaudeCodeRequest request) throws IOException {
        List<String> command = buildClaudeCommand(request);

        ProcessBuilder processBuilder = new ProcessBuilder(command);

        // Set working directory if projectPath provided
        if (request.projectPath() != null && !request.projectPath().isBlank()) {
            java.io.File projectDir = new java.io.File(request.projectPath());
            if (projectDir.exists() && projectDir.isDirectory()) {
                processBuilder.directory(projectDir);
                logger.debug("Set working directory to: {}", projectDir.getAbsolutePath());
            } else {
                logger.warn("Project path does not exist or is not directory: {}, using default working directory",
                           request.projectPath());
            }
        }

        processBuilder.environment().put("CLAUDE_CODE_MAX_OUTPUT_TOKENS",
            System.getenv().getOrDefault("CLAUDE_CODE_MAX_OUTPUT_TOKENS", CLAUDE_CODE_MAX_OUTPUT_TOKENS));
        processBuilder.environment().put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        processBuilder.environment().put("DISABLE_NON_ESSENTIAL_MODEL_CALLS", "1");

        if (request.thinkingBudgetTokens() != null && request.thinkingBudgetTokens() > 0) {
            logger.debug("Setting MAX_THINKING_TOKENS={} for extended thinking", request.thinkingBudgetTokens());
            processBuilder.environment().put("MAX_THINKING_TOKENS",
                request.thinkingBudgetTokens().toString());
        }

        // Remove ANTHROPIC_API_KEY to let Claude Code resolve auth itself
        processBuilder.environment().remove("ANTHROPIC_API_KEY");

        logger.debug("Starting Claude CLI: {}", String.join(" ", command));

        return processBuilder.start();
    }

    // Claude Code built-in tools that we want to disable in user-controlled mode
    // Spring AI will handle tool execution through its own beans
    private static final String DISABLED_CLAUDE_CODE_TOOLS = String.join(",",
        "Task",
        "Bash",
        "Glob",
        "Grep",
        "LS",
        "ExitPlanMode",
        "Read",
        "Edit",
        "MultiEdit",
        "Write",
        "NotebookRead",
        "NotebookEdit",
        "WebFetch",
        "TodoRead",
        "TodoWrite",
        "WebSearch",
        "BashOutput",
        "KillShell",
        "SlashCommand"
    );

    private List<String> buildClaudeCommand(ClaudeCodeRequest request) {
        List<String> command = new ArrayList<>();
        command.add(claudeCodePath);

        // Print mode - read from stdin, output stream-json
        command.add("-p");

        // Stream JSON output format only (no input-format needed with -p)
        command.add("--output-format");
        command.add("stream-json");

        // Verbose output
        command.add("--verbose");

        // Permission mode
        command.add("--permission-mode");
        command.add("acceptEdits");

        // System prompt (using append-system-prompt as in original implementation)
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            command.add("--append-system-prompt");
            command.add(request.systemPrompt());
        }

        // User-controlled mode: disable Claude Code built-in tools
        // Spring AI will manage tool execution through ToolCallingManager and registered beans
        // Use custom list if provided, otherwise use default disabled tools
        List<String> toolsToDisable = request.disallowedTools() != null && !request.disallowedTools().isEmpty()
                ? request.disallowedTools()
                : List.of(DISABLED_CLAUDE_CODE_TOOLS.split(","));

        if (!toolsToDisable.isEmpty()) {
            command.add("--disallowedTools");
            command.add(String.join(",", toolsToDisable));
        }

        // Single turn per request (Spring AI manages recursive tool execution loop)
        // Extended thinking works WITHIN each turn, context preserved via conversation history
        command.add("--max-turns");
        command.add("1");

        // Model
        command.add("--model");
        command.add(request.model());

        return command;
    }

    private void writeMessagesToStdin(Process process, List<ClaudeMessage> messages) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8)) {

            // With -p flag, send messages as JSON array (not JSONL)
            // Format: [{"role": "user", "content": [...]}, {"role": "assistant", "content": [...]}, ...]
            String messagesJson = objectMapper.writeValueAsString(messages);
            logger.debug("Writing messages array to stdin ({} messages, {} chars)",
                messages.size(), messagesJson.length());

            writer.write(messagesJson);
            writer.flush();
        }
        // Closing writer closes stdin - tells Claude CLI we're done sending input
    }

    private ClaudeCodeStreamResponse parseStreamLine(String line) throws IOException {
        return objectMapper.readValue(line, ClaudeCodeStreamResponse.class);
    }

    public String getClaudeCodePath() {
        return claudeCodePath;
    }

    public Duration getTimeout() {
        return timeout;
    }
}
