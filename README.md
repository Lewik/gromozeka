# Gromozeka 🤖

**Multi-armed AI agent** - powerful desktop AI assistant built with Kotlin Multiplatform and Claude Code CLI integration.

Named after the multi-armed character from "The Mystery of the Third Planet", Gromozeka is not just another chatbot, but a "handy" agent with multiple capabilities for interacting with system, services, and various APIs.

## 🚀 Key Features

- **Rich Tool Ecosystem**: Claude Code CLI integration provides access to extensive tool library
- **MCP Support**: Model Context Protocol for modular capability extension  
- **Desktop-First**: JetBrains Compose Desktop UI optimized for developer workflow
- **Voice Integration**: Speech-to-text input via Spring AI
- **Streaming Responses**: Real-time chat with live UI updates
- **Session Persistence**: Context-aware conversations with automatic session management
- **Plugin Architecture**: Extensible "arms" system for adding new capabilities

## 🏗️ Architecture

**Core Stack:**
- **UI**: JetBrains Compose Desktop
- **AI Engine**: Claude Code CLI wrapper + Spring AI hybrid approach
- **Backend**: Kotlin/Spring Boot with SqlDelight for data persistence
- **Integration**: ProcessBuilder-based streaming wrapper with JSON output parsing

**Key Components:**
- `ClaudeCodeStreamingWrapper` - Real-time bidirectional communication
- `SessionListService` - Persistent session management  
- `McpServers` - MCP server configuration and management
- `PluginService` - Extensible capability system

## 🔧 Claude Code Integration

This project solves several Claude Code CLI integration challenges:

- **Streaming Mode**: Real-time stdin/stdout communication without hanging
- **Session Management**: Proper handling of `--resume` and `--continue` flags
- **Tool Access Control**: Configurable `--allowedTools` for security
- **MCP Configuration**: Dynamic `--mcp-config` server loading
- **JSON Output**: Structured response parsing via `--output-format json`

## 🚀 Quick Start

**Prerequisites:**
- Claude Code CLI installed and configured
- JDK 17+
- Gradle 8+

**Run:**
```bash
# Build project
./gradlew build

# Run application
./gradlew :bot:run

# Run tests  
./gradlew test
```

**Configuration:**
1. Copy `bot/src/jvmMain/resources/application.yaml.dist` to `application.yaml`
2. Configure your API keys and settings
3. Customize MCP servers in `src/jvmMain/resources/mcp.json`

## 📚 Documentation

- [docs/](./docs/) - Technical documentation and integration notes
- [CLAUDE.md](./CLAUDE.md) - Claude Code project configuration
- [Architecture Overview](./bot/doc/general.puml) - System design diagram

## 🎯 Philosophy

**Maximum practical utility through integration with real tools.** The agent should not only talk but also perform tasks - work with files, send requests, manage system, integrate with external services.

**Why Claude Code?** While alternatives like DeepSeek V3 are cheaper per token, Claude Code provides rich out-of-the-box ecosystem (tools, sub-agents, MCP servers, IDE integration) that would be expensive to replicate. The developer experience and built-in capabilities justify the cost.

## 🧪 Development Status

Research project exploring Claude Code CLI integration patterns and implementing robust streaming wrapper for JVM-based applications. Core functionality working, plugin system in development.

---

*"In the hands of a skilled developer, even the most complex system becomes simple tools"* 🛠️