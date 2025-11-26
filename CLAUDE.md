# Emergency Repair Guide (Plan B)

This file provides **minimal context** for repairing Gromozeka when normal agent system is broken. For regular development, use specialized agents in `presentation/src/jvmMain/resources/agents/`.

## Repository Instances

**Three separate instances:**

- **dev/** - main branch, dev-data/client/.gromozeka
- **beta/** - main branch (synced from dev), dev-data/client/.gromozeka
- **release/** - release branch, ~/.gromozeka/ (stable production)

**⚠️ CRITICAL: Beta Update Policy**
- NEVER edit beta/ directly!
- Beta updates ONLY via git: `cd ~/code/gromozeka/beta && git pull`
- All changes: dev/ → commit → push → beta/ sync

## Launch Commands

**Development:**
```bash
GROMOZEKA_MODE=dev ./gradlew :presentation:run
```


## Critical Build Commands

**Verify compilation + Spring context:**
```bash
./gradlew :presentation:build :presentation:jvmTest --tests ApplicationContextTest -q || \
  ./gradlew :presentation:build :presentation:jvmTest --tests ApplicationContextTest
```

**Quiet mode pattern:** Always `-q` first (80-90% token savings), full output only on error.

## Core Architecture

**Module structure:**
```
:domain                    - Pure Kotlin, NO dependencies
:application               → :domain
:infrastructure-db         → :domain
:infrastructure-ai         → :domain
:presentation              → :domain, :application
```

**Spring AI flow:**
```
Spring AI ChatClient → ClaudeCodeChatModel → Claude CLI
Tools via ToolCallingManager (unified permission checks)
```

**See:** `presentation/src/jvmMain/resources/agents/architecture.md` for complete architecture.

## MCP SDK (Two Separate!)

**Java MCP SDK** (Spring AI internal):
- Package: `io.modelcontextprotocol.sdk.*`
- Version: 0.10.0
- Usage: Spring AI uses internally

**Kotlin MCP SDK** (our implementation):
- Package: `io.modelcontextprotocol.kotlin.sdk.*`
- Version: 0.6.0
- Usage: Our MCP server implementation

**No conflicts** - different package namespaces!

## Logging

**Development:**
- Location: `logs/dev.log` (overwritten on each start)
- Use `grep` or `tail` for large files

**Production (platform-specific):**
- macOS: `~/Library/Logs/Gromozeka/gromozeka.log`
- Windows: `~/AppData/Local/Gromozeka/logs/gromozeka.log`
- Linux: `~/.local/share/Gromozeka/logs/gromozeka.log`

## Spring AI SNAPSHOT

**Warning:** Spring AI 1.1.0-SNAPSHOT auto-updates daily. Breaking changes possible.

**Recent changes:**
- Message builders: `AssistantMessage.builder().content().build()`
- TTS API: `TextToSpeechPrompt` (speed parameter is `Double`)

**Migration:** Update code when breaking changes occur (typically 1-2 times/month).

## Architecture Decision Records

**Location:** `docs/adr/`

**Structure:**
```
docs/adr/
  ├── template.md          - ADR template
  ├── domain/              - Architect Agent
  ├── infrastructure/      - Repository, Spring AI Agents
  ├── application/         - Business Logic Agent
  ├── presentation/        - UI Agent
  └── coordination/        - Build/Release, Meta-Agent
```

## Specialized Development Agents

**For regular development work, use agents:**

Location: `presentation/src/jvmMain/resources/agents/`

- `architect-agent.md` - Domain layer design
- `repository-agent.md` - Infrastructure/DB implementation
- `business-logic-agent.md` - Application layer
- `spring-ai-agent.md` - AI/MCP integration
- `ui-agent.md` - Compose Desktop UI
- `build-release-agent.md` - Build/version/packaging
- `agent-builder-v2.md` - Meta-agent for creating new agents

**See:** `presentation/src/jvmMain/resources/agents/README.md` for agent system overview.

## Version Management

**To update version:**
1. Edit `build.gradle.kts` → change `projectVersion` (line ~25)
2. Commit change
3. Create git tag: `git tag v1.2.3`
4. Push: `git push origin main v1.2.3`

**Format:** X.Y.Z (numeric only for macOS compatibility)

**See:** Build/Release Agent for details.
