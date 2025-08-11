# Gromozeka

<img src="bot/src/jvmMain/resources/logos/logo-128x128.png" alt="Gromozeka Logo" width="64" height="64" align="left" />

**Not just another chatbot** - a "handy" agent with multiple capabilities for interacting with system, services, and APIs through advanced voice interface.

Named after the multi-armed character from "The Mystery of the Third Planet". Currently a wrapper around Claude Code CLI that adds sophisticated voice interaction. Designed for future multi-client architecture.

## ✨ What Gromozeka Adds

### 🎤 Advanced Voice Interface (PTT)
- **Global hotkey**: Press `§` (paragraph key) anywhere in macOS to talk to AI
- **Zero conflicts**: Uses innovative key remapping to avoid system shortcut conflicts
- **Multiple input patterns**: Click, double-click, hold variations for different actions  
- **Audio management**: Automatic background music muting during recording

### 🖥️ Desktop Interface
- **Native UI**: JetBrains Compose Desktop chat interface
- **Multiple sessions**: Handle concurrent conversations (work in progress)
- **Context management**: Create and organize conversation contexts (work in progress)
- **Always available**: System-wide accessibility without switching contexts

### 🔧 Claude Code Integration
- **Streaming wrapper**: Real-time communication with Claude Code CLI via stdout/stdin

**Note**: All AI capabilities (tool calling, file operations, web search, etc.) are provided by Claude Code CLI itself. Gromozeka wraps Claude Code CLI to add voice interaction.

## 🚀 Getting Started

### Prerequisites
- **macOS**: Currently optimized for macOS (Windows/Linux support planned)
- **Claude Code CLI**: Install from [Claude Code documentation](https://docs.anthropic.com/en/docs/claude-code)
- **Microphone access**: For voice input features

### Installation & Setup

1. **Clone the repository**:
   ```bash
   git clone https://github.com/Lewik/gromozeka
   cd gromozeka/dev
   ```

2. **Configure Claude Code**:
   - Set up your Anthropic API key
   - Ensure `claude` command works in terminal

3. **Build and run**:
   ```bash
   ./gradlew :bot:build
   ./gradlew :bot:run
   ```

4. **Enable permissions** (macOS):
   - **Microphone**: For voice input
   - **Accessibility**: For global § key hotkey

## 🎮 How to Use

### Voice Interaction
- **Quick talk**: Press and hold `§` key, speak, release to send
- **Stop AI**: Quick press `§` to stop TTS playback
- **Interrupt & talk**: Double-click + hold `§` to interrupt AI and provide new input
- **On-screen button**: Alternative to global hotkey in the UI

### Chat Interface
- Type messages normally in the text input
- **Tool integration**: AI automatically uses appropriate tools for your requests
- **Real-time streaming**: Watch responses appear in real-time

## 🏗️ Architecture

### Core Components
- **ClaudeCodeStreamingWrapper**: Manages Claude CLI process and streaming communication
- **PTT System**: Advanced voice input with global hotkeys
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

### Building
```bash
# Compile and test
./gradlew :bot:compileKotlinJvm -q && ./gradlew :bot:allTests -q

# Full build
./gradlew :bot:build

# Run application  
./gradlew :bot:run
```

### Project Structure
```
bot/           # Main application module
shared/        # Shared models and utilities  
docs/          # Architecture documentation
├── ptt-system.md          # PTT implementation details
└── claude-code-*.md       # Claude Code integration notes
```

### Contributing
1. Check existing [GitHub Issues](https://github.com/Lewik/gromozeka/issues) for planned features
2. Focus on real-world utility and desktop UX
3. Test voice features on actual hardware
4. Follow Kotlin/Compose best practices

## 🔮 Future Roadmap

### Short-term
- **Complete multi-session support**: Finish concurrent conversation handling
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