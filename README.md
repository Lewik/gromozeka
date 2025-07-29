# Chat

AI chat application built with Kotlin Multiplatform and JetBrains Compose Desktop, featuring Claude Code CLI integration.

## Features

- **Desktop UI**: JetBrains Compose Desktop interface
- **Claude Code Integration**: Direct integration with Claude Code CLI via streaming wrapper
- **Voice Input**: Speech-to-text functionality
- **Session Management**: Persistent chat sessions with Claude Code
- **Real-time Updates**: Streaming responses with live UI updates

## Architecture

- **Backend**: Kotlin/Spring Boot with Spring AI
- **Frontend**: JetBrains Compose Desktop
- **AI Integration**: Claude Code CLI wrapper with streaming support
- **Session Storage**: Local JSONL files managed by Claude Code

## Claude Code Integration

This project includes a custom streaming wrapper for Claude Code CLI that solves several integration challenges:

- **ProcessBuilder Hanging**: Fixed issue with `--print` flag hanging in subprocess mode
- **Session Management**: Proper handling of Claude Code's session behavior
- **Streaming Mode**: Real-time bidirectional communication via stdin/stdout
- **Context Preservation**: Automatic session loading and continuation

See [docs/](./docs/) for detailed technical documentation on Claude Code integration.

## Quick Start

```bash
# Build and run
./gradlew :chat:run

# Run tests
./gradlew test
```

## Documentation

- [docs/](./docs/) - Claude Code integration technical notes
- [CLAUDE.md](./CLAUDE.md) - Project configuration for Claude Code

## Development

Built as a research project to understand Claude Code CLI integration patterns and implement a robust streaming wrapper for JVM-based applications.