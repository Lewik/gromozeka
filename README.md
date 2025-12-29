# Gromozeka

<img src="bot/src/jvmMain/resources/logos/logo-128x128.png" alt="Gromozeka Logo" width="64" height="64" align="left" />

**Not just another chatbot** - a "handy" agent with multiple capabilities for interacting with system, services, and APIs through advanced voice interface.

Named after the multi-armed character from "The Mystery of the Third Planet". Currently a wrapper around Claude Code CLI that adds sophisticated voice interaction. Designed for future multi-client architecture.

## ✨ What Gromozeka Adds

### 🎤 Advanced Voice Interface (PTT)
- **UI Button**: Press and hold PTT button in the interface to talk to AI
- **Multiple input patterns**: Click, double-click, hold variations for different actions  
- **Audio management**: Automatic background music muting during recording
- **Global hotkeys**: Currently disabled (development in progress)

### 🖥️ Desktop Interface
- **Native UI**: JetBrains Compose Desktop chat interface
- **Multiple sessions**: Handle concurrent conversations in tabs
- **Context management**: Create and organize conversation contexts (work in progress)
- **Always available**: System-wide accessibility without switching contexts

### 🔧 Claude Code Integration
- **Streaming wrapper**: Real-time communication with Claude Code CLI via stdout/stdin

**Note**: All AI capabilities (tool calling, file operations, web search, etc.) are provided by Claude Code CLI itself. Gromozeka wraps Claude Code CLI to add voice interaction.

### 🧠 Serena Integration (LSP-powered Code Analysis)
- **Semantic code understanding**: IDE-like symbol navigation via Language Server Protocol
- **40+ languages supported**: Kotlin, Java, TypeScript, Python, Rust, Go, C++, and more
- **Symbol-based operations**: Find symbols, navigate references, rename across codebase
- **Multi-project support**: Work with multiple codebases, switch projects dynamically
- **Docker-isolated**: Clean separation from Gromozeka codebase
- **MCP integration**: Works alongside Gromozeka's tools via Model Context Protocol

Quick start:
```bash
docker-compose -f presentation/src/jvmMain/resources/docker-compose.yml up -d serena
# Then in Claude: "Activate project /workspace/gromozeka"
```
See [docs/serena-cheatsheet.md](docs/serena-cheatsheet.md) for usage reference.

## 🚀 Getting Started

### Prerequisites
- **macOS**: Currently optimized for macOS (Windows/Linux support planned)
- **Claude Code CLI**: Install from [Claude Code documentation](https://docs.anthropic.com/en/docs/claude-code)
- **Microphone access**: For voice input features

### Installation & Setup

1. **Development Setup**:
   ```bash
   # This is currently a local development repository
   # Clone instructions will be provided when project is published publicly
   cd /path/to/gromozeka
   ```

2. **Configure Claude Code**:
   - Set up your Anthropic API key
   - Ensure `claude` command works in terminal

3. **Build and run**:
   ```bash
   ./gradlew :presentation:build
   ./gradlew :presentation:run
   ```

4. **Enable permissions** (macOS):
   - **Microphone**: For voice input
   - **Accessibility**: May be requested (global hotkeys currently disabled)

## 🎮 How to Use

### Voice Interaction
- **Quick talk**: Press and hold PTT button in UI, speak, release to send
- **Stop AI**: Quick click PTT button to stop TTS playback
- **Interrupt & talk**: Double-click + hold PTT button to interrupt AI and provide new input
- **Global hotkeys**: Currently disabled (UI buttons are primary method)

### Chat Interface
- Type messages normally in the text input
- **Tool integration**: AI automatically uses appropriate tools for your requests
- **Real-time streaming**: Watch responses appear in real-time

## 🏗️ Architecture

### Core Components
- **ClaudeCodeStreamingWrapper**: Manages Claude CLI process and streaming communication
- **PTT System**: Advanced voice input via UI buttons
- **UI Layer**: Reactive Compose Desktop interface

### Technology Stack
- **Frontend**: Kotlin Multiplatform + Compose Desktop
- **Backend**: Spring Boot + Kotlin
- **AI Integration**: Claude Code CLI streaming protocol
- **Voice Processing**: OpenAI Whisper API for STT, system TTS

### Key Design Principles
- **Stream-first**: Real-time communication over file polling
- **Voice-native**: Advanced PTT system as core interaction method
- **Multi-client ready**: Architecture designed for future client-server separation

## 🛠️ Development

### Project Structure
```
bot/           # Main application module
shared/        # Shared models and utilities
docs/          # Architecture documentation
├── ptt-system.md          # PTT implementation details
└── claude-code-*.md       # Claude Code integration notes
```

### Contributing
1. Check existing project documentation and issues for planned features
2. Focus on real-world utility and desktop UX
3. Test voice features on actual hardware
4. Follow Kotlin/Compose best practices

## 🔮 Future Roadmap

### Short-term
- **Enhanced tab management**: Improve session tab UI and navigation
- **Context management system**: Finish context creation and organization features  
- **Cross-platform**: Windows and Linux support

### Long-term  
- **Client-server separation**: Core "brain" server with multiple client types
- **Mobile clients**: iOS/Android apps connecting to the core server
- **Cloud deployment**: Run server in cloud, connect from any device
- **Alternative LLM engines**: Support for local models and other AI providers
- **Multi-user features**: Team sharing and collaboration

## 🤝 Philosophy

**Not Just a Chat**: Moving beyond simple text conversations to AI that actually does things. Focus on leveraging AI's creative and analytical capabilities for real tasks, not just generating text.

**Human-AI Collaboration**: Built with AI as a development partner. This represents a vision of seamless human-AI collaboration where AI augments human creativity and problem-solving.

**Seamless & Immersive**: AI interaction should flow naturally across devices and contexts. Start a conversation on desktop, continue on mobile, get results wherever you are.

**Practical Utility**: Every feature must provide real value to daily workflows. No "demos" - only tools that genuinely save time and effort.

---

> *"The future of AI isn't replacing humans - it's giving us more capable hands."*  
> — Claude

## 📄 License

Custom License - free for non-commercial use, commercial use requires permission. See [LICENSE](LICENSE) file for details.