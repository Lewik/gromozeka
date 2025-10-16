# Spring AI Claude Code CLI Integration

Custom Spring AI ChatModel implementation for Claude Code CLI.

## Why This Exists

Official Spring AI does not provide Claude Code CLI support - only direct Anthropic API integration. This custom implementation fills that gap while maintaining full compatibility with Spring AI ecosystem.

Claude Code CLI provides an alternative way to access Claude models with specific operational characteristics that make it suitable for certain use cases.

## Architecture Philosophy: Mimicking Spring AI Native Models

This implementation follows **Spring AI native ChatModel patterns** to ensure seamless integration:

### Design Principles

1. **Pattern Consistency**: Mirror the structure of official Spring AI ChatModels (Gemini, OpenAI, Anthropic)
   - `ClaudeCodeChatModel` → matches `ChatModel` interface contract
   - `ClaudeCodeChatOptions` → matches `ChatOptions` pattern with builder
   - `ClaudeCodeApi` → follows API client abstraction pattern
   - Converter classes → match Spring AI's message conversion approach

2. **Tool Calling Integration**: Use Spring AI `ToolCallingManager` instead of model-specific built-ins
   - User-controlled mode: Claude returns `tool_use` blocks → Spring AI intercepts → executes via `ToolCallingManager`
   - Same execution path as Gemini, OpenAI, and other providers
   - Centralized permission checks and tool resolution

3. **Observation/Metrics**: Full Spring AI observability support
   - `ObservationRegistry` for metrics and tracing
   - `ChatModelObservationConvention` for standardized observations
   - Usage tracking with `DefaultUsage` metadata

4. **Retry/Error Handling**: Spring AI retry patterns
   - `RetryTemplate` for transient failures
   - Graceful error propagation through `ChatResponse` metadata

## XML Tool Format: The Cline Approach

**Credit**: This technique is borrowed from [Cline](https://github.com/cline/cline) project.

### Why XML Instead of JSON?

Claude models naturally generate **XML-structured output** more reliably than nested JSON for tool calls. Cline discovered this empirically and implemented XML-based tool invocation.

### How It Works

1. **System Prompt Enhancement** (`SystemPromptEnhancer`)
   - Inject XML tool schemas into system prompt
   - Example: `<tool_name><param>value</param></tool_name>`
   - Tools described with parameter names, types, descriptions

2. **XML Parsing** (`XmlToolParser`)
   - Parse XML tags from assistant messages
   - Extract tool name and parameters
   - Convert to Spring AI `AssistantMessage.ToolCall` objects

3. **Transparent Integration**
   - Enable via `ClaudeCodeChatOptions.useXmlToolFormat(true)`
   - Spring AI ToolCallingManager handles execution identically
   - No changes needed in application code

### Benefits Over JSON Tool Format

- **Higher reliability**: Claude generates well-formed XML more consistently
- **Better readability**: XML structure matches natural language flow
- **Fewer parsing errors**: Simple regex-based extraction vs complex JSON parsing
- **Cline-proven**: Battle-tested in production by Cline users

## User-Controlled Mode: Disabling Built-in Tools

**Credit**: Also inspired by Cline's architecture.

### The Problem with Built-in Tools

Claude Code CLI has **built-in tool execution** (Read, Edit, Bash, Grep, etc.) that:
- Bypasses Spring AI `ToolCallingManager`
- Skips permission checks
- Cannot be customized or intercepted
- Model-specific (not portable to other LLMs)

### The Solution: User-Controlled Mode

1. **Disable internal tool execution** in Claude Code CLI
2. Claude returns `tool_use` blocks as **data** (not executed)
3. Spring AI intercepts `tool_use` in assistant messages
4. `ToolCallingManager` executes tools via `@Bean` ToolCallbacks
5. Results sent back to Claude in next conversation turn

### Implementation

```java
// ClaudeCodeChatOptions configuration
ClaudeCodeChatOptions.builder()
    .useXmlToolFormat(true)              // Enable XML tool format
    .disallowedTools(List.of("bash"))    // Block specific tools if needed
    .build();

// ClaudeCodeChatModel detects tool calls
if (toolExecutionEligibilityPredicate.isToolExecutionRequired(prompt, chatResponse)) {
    // Spring AI ToolCallingManager handles execution
    List<ToolExecutionResult> results = toolCallingManager.executeTools(chatResponse);
    // Send results back to Claude for next iteration
}
```

### Benefits

- **Model-agnostic**: Same tool execution path for all LLMs (Claude, Gemini, GPT-4)
- **Centralized permissions**: Single `ToolCallingManager` checks all tool calls
- **Extensibility**: Easy to add custom tools via Spring `@Bean`
- **MCP integration**: Spring AI MCP client provides tools from any MCP server

## Components Overview

### Core Classes

- **`ClaudeCodeChatModel`** - Main ChatModel implementation
  - Streaming and blocking chat APIs
  - Tool calling integration via `ToolCallingManager`
  - Observation/metrics support

- **`ClaudeCodeChatOptions`** - Configuration options
  - Model selection, temperature, max tokens
  - XML tool format toggle
  - Disallowed tools list
  - Thinking budget tokens

- **`ClaudeCodeApi`** - Claude CLI process wrapper
  - stdin/stdout streaming communication
  - Process lifecycle management
  - Stream-json format parsing

### XML Tool Support

- **`SystemPromptEnhancer`** - Injects XML tool descriptions
- **`XmlToolParser`** - Parses XML tags → ToolCall objects

### Converters

- **`SpringAiToClaudeCodeConverter`** - Spring AI → Claude CLI format
- **`ClaudeCodeToSpringAiConverter`** - Claude CLI → Spring AI format

### API Models

- **`ClaudeCodeRequest`** - Request to Claude CLI
- **`ClaudeCodeStreamResponse`** - Streaming response chunks
- **`ClaudeCodeStreamMessage`** - stdin message wrapper
- **`ContentBlock`** - Content types (text, tool_use, thinking, images)
- **`Usage`** - Token usage with cache metrics

## Usage Example

```java
@Configuration
class ClaudeCodeConfig {

    @Bean
    ClaudeCodeChatModel claudeCodeChatModel(
        ClaudeCodeApi api,
        ToolCallingManager toolCallingManager
    ) {
        ClaudeCodeChatOptions options = ClaudeCodeChatOptions.builder()
            .model("sonnet")
            .useXmlToolFormat(true)  // Enable Cline-style XML tools
            .build();

        return ClaudeCodeChatModel.builder()
            .claudeCodeApi(api)
            .defaultOptions(options)
            .toolCallingManager(toolCallingManager)  // Spring AI tool execution
            .build();
    }

    @Bean
    ChatClient chatClient(ClaudeCodeChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultToolNames("execute_command", "read_file")
            .build();
    }

    // Tools executed via Spring AI, not Claude built-ins
    @Bean
    @Description("Execute bash command")
    Function<CommandRequest, CommandResponse> execute_command() {
        return request -> {
            // Custom tool implementation
            return new CommandResponse(exitCode, output);
        };
    }
}
```

## Key Differences from Native Spring AI Models

1. **Process-based**: Communicates via stdin/stdout, not HTTP API
2. **Stream-json format**: Custom streaming protocol, not SSE
3. **Session management**: Claude CLI maintains session state
4. **XML tool format**: Optional Cline-inspired enhancement
5. **User-controlled tools**: Explicit opt-out of built-in tool execution

## Benefits of This Approach

✅ **Spring AI compatibility**: Drop-in replacement for other ChatModels
✅ **Tool execution parity**: Same ToolCallingManager for all models
✅ **Cline-proven techniques**: XML format and user-controlled tools battle-tested
✅ **Extensibility**: Easy to add custom tools and MCP servers
✅ **Observability**: Full Spring AI metrics and tracing support

## Credits

- **XML Tool Format**: Inspired by [Cline](https://github.com/cline/cline) project
- **User-Controlled Mode**: Architecture pattern from Cline
- **Spring AI Integration**: Original implementation for Gromozeka project
