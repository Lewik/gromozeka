# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Version Management

**To update application version:**
1. Edit `build.gradle.kts` in the root directory
2. Change the `projectVersion` variable (line ~25)
3. This automatically updates:
   - JAR file names (bot-jvm-X.X.X.jar)
   - Package versions for all platforms (DMG, MSI, DEB, RPM)
   - Info.plist version on macOS
4. Create and push a git tag with matching version (e.g., v1.1.3)
5. GitHub Actions will build and release automatically

**For beta versions:**
- **Use numeric-only format** (e.g., `1.1.7`, `1.1.8`) due to macOS CFBundleVersion/jpackage limitations
- **macOS packaging requires** pure `X.Y.Z` format - no alpha/beta suffixes supported by Apple
- **Beta strategy:** Increment Z (patch) for beta → increment Y (minor) for stable
- **Git tags contain beta info:** Use `v1.1.7beta1` for tagging (informative only)
- **Version progression example:** `1.1.6` (stable) → `1.1.7` (beta) → `1.2.0` (next stable)
- **Multiple betas:** `1.1.7`, `1.1.8`, `1.1.9` (all beta) → `1.2.0` (stable release)

## Project Structure

This is a Kotlin Multiplatform Project (MPP) with the following modules:
- `bot` - The main application module containing the gromozeka bot application
- `shared` - A library module with shared code and models
- `docs/` - Project documentation including architectural decisions and philosophy
- `logs/` - Development logs directory (ignored in git)

## Development Logging

**Log Files Location**: `logs/dev.log` - All development logs are written to this file during development.

**Important Notes**:
- Logs are **overwritten on each application start** during development
- Log files can be large - use `grep`, `tail`, or other text search tools to find relevant information
- Various debug categories are used throughout the codebase for different components and issues

**Usage**: Monitor `logs/dev.log` during development to debug performance issues, memory leaks, and thread pool problems.

## Production Logging

**Production Log Locations** (platform-specific):
- **macOS**: `~/Library/Logs/Gromozeka/gromozeka.log`
- **Windows**: `~/AppData/Local/Gromozeka/logs/gromozeka.log`  
- **Linux**: `~/.local/share/Gromozeka/logs/gromozeka.log`

**Production Log Configuration**:
- Rolling policy: 100MB files, 30 days history, max 3GB total
- Log level: INFO for application, WARN for frameworks
- No console output (file only)

## Build and Test Commands


**Individual commands** (for reference):
- Build project: `./gradlew :bot:build`
- Run application: `./gradlew :bot:run`
- Run all tests: `./gradlew :bot:allTests -q || ./gradlew :bot:allTests`
- Clean build: `./gradlew clean`
- Build check: `./gradlew :bot:build -q || ./gradlew :bot:build`

**Gradle optimization**: Always use quiet mode first (`-q`) for token efficiency, then full output only if errors occur.

**Workflow rationale**: The combined command provides quick compilation feedback first, then comprehensive test validation, with clear diagnostic separation.

**CRITICAL**: Never run `./gradlew :bot:run` unless user explicitly requests it or gives consent. Always ask permission before starting the application.

## AppImage Distribution (Linux)

**AppImage build** (Linux only):
- `./gradlew buildAppImage` - Build AppImage distribution
- `./build-appimage.sh` - Direct script execution
- `./build-appimage.sh --clean` - Clean build artifacts

**Requirements**:
- Linux system (x86_64 architecture)
- Java Development Kit (JDK 21+)
- `curl` or `wget` for downloading appimagetool

**Build Process**:
1. Automatically downloads `appimagetool` if not present
2. Temporarily enables `TargetFormat.AppImage` in Gradle build
3. Builds Compose Desktop application with embedded JRE
4. Creates AppDir structure with AppRun launcher and desktop integration
5. Converts AppDir to final AppImage using appimagetool
6. Restores original Gradle configuration

**Output**: `build/appimage/Gromozeka-{version}-x86_64.AppImage`

**AppImage Features**:
- **Embedded JRE**: No Java installation required on target system
- **System Claude CLI Detection**: Automatically finds and validates Claude Code CLI
- **Desktop Integration**: Appears in application menus with proper icon
- **Cross-Distribution Compatibility**: Works on Ubuntu, Fedora, openSUSE, Arch, etc.
- **Error Handling**: User-friendly dialogs for missing dependencies

**Architecture**: 
- AppRun launcher script handles Claude CLI discovery and Java environment setup
- Embedded OpenJDK 21 runtime for maximum compatibility (glibc 2.17+)
- Desktop file provides proper application registration and MIME types
- Robust dependency validation (architecture, glibc version, Claude CLI availability)

**Troubleshooting**:
- **"Claude Code CLI not found"**: Install Claude CLI and ensure it's in PATH
- **"Unsupported architecture"**: AppImage is built for x86_64 only
- **"Incompatible glibc version"**: Target system needs glibc 2.17+ (Ubuntu 14.04+)

## Test Status

**Current Test State**: All tests are currently **disabled** using `@Disabled` annotation. There are no active/enabled tests running in CI.

**Test Categories**:
- `deserialization/` - Tests for JSON parsing and deserialization (5 files)  
- `integration/` - Integration and statistics tests (1 file)
- `research/` - Research and data analysis tests (4 files)
- `testdata/` - Test data objects and structures (2 files)  
- `utilities/` - Utility tests like logo generation (1 file)

**Note**: Tests are disabled for research purposes and should not run in CI. They can be manually enabled for development/debugging by removing `@Disabled` annotations.

**Why Few Tests**: Kotlin is a statically typed language with strong compile-time guarantees. Many potential runtime errors are caught at compilation time through:
- Null safety (nullable types)
- Type safety and inference  
- Exhaustive when expressions
- Data class immutability
- Smart casting

This significantly reduces the need for extensive unit testing compared to dynamically typed languages. **Do not create tests for obvious type-safe operations or basic Kotlin language features** - the compiler already validates these.

## Project Overview

**Concept**: Gromozeka - multi-armed AI agent (named after the character from "The Mystery of the Third Planet"). This is not just another chatbot, but a "handy" agent with multiple capabilities for interacting with system, services, and various APIs.

**Core Philosophy**: Maximum practical utility through integration with real tools. The agent should not only talk but also perform tasks - work with files, send requests, manage system, integrate with external services.

**Architecture Stack**:
- **UI**: JetBrains Compose Desktop
- **AI Integration**: Claude Code CLI streaming integration
- **Core Features**: Text chat, voice input (PTT), session management, streaming UI

**Key Differentiators**:
- Advanced voice interface wrapper for AI interaction
- Sophisticated PTT (Push-to-Talk) system via UI buttons  
- Multiple session management with history and resume support in tabs
- Context management and organization (work in progress)

**Naming**: "Gromozeka" - internal codename, may be changed for release

## Current Architecture

### Core Integration: Claude Code CLI Streaming

**Key components**:
1. **ClaudeCodeStreamingWrapper** - Process management and stream communication
2. **Session** - Session lifecycle, message handling, and state management  
3. **ClaudeStreamToChatConverter** - Maps Claude stream format to internal ChatMessage format
4. **SessionJsonlService** - Historical session loading from .jsonl files for resume functionality

**Claude Code CLI invocation**:
```bash
claude --output-format stream-json --input-format stream-json --verbose --permission-mode acceptEdits --append-system-prompt <SYSTEM_PROMPT>
```

**Streaming Protocol**:
- **Input**: JSON messages sent to Claude CLI stdin
- **Output**: Stream of JSONL messages from Claude CLI stdout  
- **Control**: Interrupt requests via control messages
- **Session Management**: Session ID extracted from `init` messages

### PTT (Push-to-Talk) System

**Current PTT Implementation** via UI button:
1. **On-screen PTT button** - Primary input method, always available in UI
2. **Global hotkeys** - Currently disabled (NoOp implementation)

**5 Gesture Types Supported**:
- **Single Click**: Stop TTS
- **Double Click**: Stop TTS + Interrupt Claude  
- **Single Hold**: Record voice message
- **Double Hold**: Interrupt Claude + Record voice message
- **Button Down**: Optimistic recording start + audio mute

**Technical Implementation**:
- **NoOpGlobalHotkeyController**: Placeholder implementation (global hotkeys disabled)
- **UnifiedGestureDetector**: Recognizes gesture patterns by timing
- **PTTEventRouter**: Routes gesture events to appropriate handlers
- **AudioMuteManager**: System audio muting during recording
- **STTService**: Speech-to-text transcription

Global hotkeys temporarily disabled. Previous implementation used § key remapped to F13 via hidutil.

### Session Management & Resume

**Resume Behavior**: 
- Claude Code CLI creates NEW session ID on resume (not reusing old ID)
- Historical messages loaded from old session .jsonl file
- Current session uses new session ID for operations
- Session state handles this dual-ID transition gracefully

**Session Lifecycle**:
1. Start Claude CLI process with streaming
2. Extract session ID from init messages
3. Load historical messages if resuming
4. Handle real-time streaming messages
5. Manage buffered messages until session initialized

**File Locations**: Claude stores session files in `~/.claude/projects/<escaped-path>/` as individual `.jsonl` files.

### Real-time Streaming Architecture

**Current Approach**: Direct stdout/stdin streaming with Claude Code CLI process.

**Stream Processing Flow**:
```
User Input → Session.sendMessage() → ClaudeWrapper.sendMessage() → Claude CLI stdin
Claude CLI stdout → parseStreamJsonLine() → ClaudeStreamToChatConverter → UI Updates
```

**Key Patterns**:
- **SharedFlow** for message streaming with replay for late subscribers
- **StateFlow** for session state and metadata
- **Mutex protection** for all mutable session operations
- **Error recovery** with graceful degradation and reconnection attempts

### Data Models

**Streaming Models** (`StreamJsonLine` sealed class):
- `StreamJsonLine.System` - Init, control, metadata messages
- `StreamJsonLine.User` - User input messages  
- `StreamJsonLine.Assistant` - Claude response messages
- `StreamJsonLine.Result` - Final response with usage metrics
- `StreamJsonLine.ControlRequest/Response` - Interrupt handling

**Unified Output** (`ChatMessage`):
- Engine-agnostic representation in shared module
- ContentItem hierarchy for different message types (text, tool calls, etc.)
- Support for historical messages, TTS text, and original JSON

**Tool Integration**:
- Full Claude Code tool support (Read, Edit, Bash, Grep, WebSearch, etc.)
- Type-safe mapping via `ClaudeCodeToolCallData`/`ClaudeCodeToolResultData`
- Internal MCP HTTP server (`McpHttpServer`) for custom tool extensibility

## Spring AI Integration Strategy

**Goal**: Model-agnostic AI integration through Spring AI unified API.

### Target Architecture

```
SessionSpringAI (business logic)
    ↓
Spring AI ChatClient (unified interface)
    ↓
ToolCallingManager (single tool execution point + permission checks)
    ↓
@Bean tools: bashTool, readTool, editTool, etc.
    ↓
Multiple ChatModel implementations:
  - Gemini (native Spring AI)
  - Claude Code CLI (custom implementation)
  - OpenAI/Anthropic API (future)
```

**Key Principles**:
- Model-agnostic: All models use same ChatClient API
- Unified tool execution: All tools execute through Spring AI ToolCallingManager with centralized permission checks, not model-specific built-ins

**Legacy Components (deprecated)**:
- `McpHttpServer` - Old MCP HTTP server for tool execution, replaced by Spring AI ToolCallingManager
- Tool invocation and permission checks now centralized in `ToolCallingManager`, not via HTTP MCP protocol

### Session Architecture

**SessionSpringAI** - Target implementation (~160 lines):
- Simple linear request-response flow
- Spring AI `ChatClient` integration
- Model-agnostic design
- Straightforward message history management

**Session** (legacy) - Current production (~500+ lines):
- Actor model with channels (priority, user, stream)
- Direct `ClaudeWrapper` integration
- Complex state machine and buffering
- Production-ready but model-specific

**Duck Typing**: Both implement identical interface (start/stop, sendMessage, messageOutputStream, events) allowing seamless switching without shared base class.

**Migration Path**: Session remains as fallback until Spring AI tool execution complete. SessionSpringAI becomes primary after user-controlled mode implemented.

### Claude Code CLI Integration (Custom Implementation)

**Location**: `bot/src/jvmMain/java/org/springframework/ai/claudecode/`

Official Spring AI does not include Claude Code CLI support. Custom implementation mimics Spring AI native ChatModel patterns for seamless compatibility.

**Tool Execution**: User-controlled mode (`internalToolExecutionEnabled=false`)
- Claude returns `tool_use` blocks in assistant messages
- Spring AI intercepts and executes via ToolCallingManager
- Same execution path as Gemini, OpenAI, other providers
- No model-specific built-in tools used

**Protocol Flow**:
1. Send message history to Claude CLI
2. Receive streaming response with `tool_use` blocks
3. Spring AI ToolCallingManager detects tool calls, checks permissions
4. Execute approved calls via ToolCallback beans
5. Send `tool_result` back to Claude
6. Continue until `stop_reason != "tool_use"`

## MCP SDK Architecture & Package Management

**CRITICAL: Two Different MCP SDKs Coexist**

The project uses **two separate MCP SDKs** that do NOT conflict due to different package namespaces:

### Java MCP SDK (via Spring AI)
- **Package**: `io.modelcontextprotocol.sdk.*`
- **Version**: 0.10.0
- **Dependency**: `org.springframework.ai:spring-ai-starter-mcp-client`
- **Purpose**: Spring AI MCP client integration
- **Usage**: Spring AI uses this internally for MCP connections

### Kotlin MCP SDK (our choice)
- **Package**: `io.modelcontextprotocol.kotlin.sdk.*` 
- **Version**: 0.6.0 (latest for Kotlin SDK)
- **Dependency**: `io.modelcontextprotocol:kotlin-sdk`
- **Purpose**: Our MCP server implementation
- **Usage**: Used in internal `McpHttpServer` implementation

### No Package Conflicts
**Key Insight**: Different package names = no classpath conflicts!
- Java SDK: `io.modelcontextprotocol.sdk.TextContent`
- Kotlin SDK: `io.modelcontextprotocol.kotlin.sdk.TextContent`

Both can coexist safely in the same project.

### When to Use Which SDK

**Use Kotlin MCP SDK for:**
- Internal MCP server implementation (`bot/services/McpHttpServer.kt`)
- Custom MCP tools and handlers  
- Type-safe MCP protocol objects (`Tool`, `CallToolResult`, `JSONRPCRequest`)
- Kotlin-specific features (coroutines, data classes)

**Use Java MCP SDK for:**
- Spring AI integration (automatic)
- Java-based MCP clients
- Interoperability with Java ecosystem

### Best Practices

**1. Import Strategy:**
```kotlin
// Kotlin MCP SDK (preferred for our code)
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson

// Use typed objects instead of raw JSON
val tool = Tool(name = "example", description = "...", inputSchema = Tool.Input())
val result = CallToolResult(content = listOf(TextContent("response")))
```

**2. Avoid Raw JSON:**
- **Bad**: `buildJsonObject { put("name", "tool") }`
- **Good**: `Tool(name = "tool", description = "...", inputSchema = Tool.Input())`

**3. Serialization:**
- Use `McpJson.encodeToString()` for Kotlin MCP SDK objects
- Use `McpJson.decodeFromString<JSONRPCRequest>()` for parsing

### Version Management
- **Kotlin SDK 0.6.0** is the latest version for Kotlin projects
- **Java SDK 0.10.0** is used by Spring AI (different project)
- Both are current and maintained
- No need to upgrade - they serve different purposes

## GitHub Issues Usage

Issues serve as our **shared project notebook** and **reference base**:

### What to put in Issues:
- 💡 **Ideas/Features**: "Add Obsidian integration", "Voice commands support"
- 🔧 **Technical problems**: "STT slow on long recordings", "Memory leaks in streaming"
- 🎯 **Architectural decisions**: "Switch to MCP for all tools", "Redesign session storage"  
- 📚 **Research notes**: "Investigate whisper.cpp vs Vosk", "Local LLM integration options"
- 🐛 **Found bugs**: "PTT fails when window minimized", "Claude Code CLI timeout issues"

### How Claude Code uses Issues:
- **Search as needed**: When relevant context is required
- **Reference during planning**: "What should we work on next?"
- **Create new ones** when discovering important items
- **Always sign** generated issues and comments with: `🤖 Generated with [Claude Code](https://claude.ai/code)`

**Language**: All GitHub Issues must be created in English (titles, descriptions, comments).

## Development Guidelines

### When Working with Sessions:
1. Always use `sessionMutex.withLock` for mutable operations
2. Test session behavior with real Claude Code processes, not mocks  
3. Handle session ID changes via init message monitoring
4. Expect nullable fields everywhere (Claude format can vary)

### When Adding New Features:
1. Follow reactive programming patterns (StateFlow/SharedFlow)
2. Add proper error handling and recovery
3. Test with real Claude Code streaming output
4. Document engine-specific behaviors in code

### Testing Strategy:
- **Unit tests**: Pure functions (parsers, mappers, utilities)
- **Integration tests**: Use real Claude Code session data from test files
- **Manual validation**: Stream behavior and PTT system require real testing
- **No mocks**: External process timing cannot be reliably mocked

## Log Encryption for Bug Reports

**How to decrypt user-submitted logs**

When users submit encrypted logs via GitHub issues, decrypt them:

```bash
age --decrypt --identity developer_private.age --output logs/logs.zip path/to/encrypted_logs.age && unzip logs/logs.zip -d logs/
```

*Tip: Use `logs/` directory - it's already gitignored*

**Key locations**:
- Public key: `bot/src/jvmMain/resources/encryption/developer_public.age`  
- Private key: `developer_private.age` (repo root, gitignored)

**User workflow**: Settings → "Encrypt Logs" → creates sanitized .age file in Gromozeka home directory
**File locations**:
- **Production mode**: `~/.gromozeka/encrypted-logs/`
- **Development mode**: `bot/dev-data/.gromozeka/encrypted-logs/`
**Security**: Personal data should not be logged, only technical diagnostics preserved

## Known Issues and Future Work

### Current Limitations:
- Full message list updates (incremental updates planned)
- Mixed serialization libraries (migration to kotlinx.serialization planned)

### Future Enhancements:
- **Plugin system**: For adding new "arms" (capabilities)
- **Client-server architecture**: Remote UI clients connecting to core "brain"
- **Alternative LLM engines**: Separate wrappers for different engines


# important-instruction-reminders
Do what has been asked; nothing more, nothing less.
NEVER create files unless they're absolutely necessary for achieving your goal.
ALWAYS prefer editing an existing file to creating a new one.
NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested by the User.