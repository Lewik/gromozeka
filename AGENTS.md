# Emergency Repair Guide (Plan B)

**You are Codex, launched with this file because Gromozeka system is broken.**

Normal agent system isn't available. User will tell you what's broken. Your job: fix it.

## Understanding the System

**Problem:** You don't know how Gromozeka works yet.

**Solution:** Read agent prompts - they contain all knowledge about the system.

## Where Agent Knowledge Lives

**Agent prompts = system documentation**

**Builtin prompts:**
```
server/src/main/resources/prompts/*.md
```

**Project prompts:**
```
.gromozeka/prompts/*.md
```

## Quick Start Strategy

**1. Get context:**
```bash
cat .gromozeka/prompts/project-common.knowledge.md
cat .gromozeka/prompts/architecture.knowledge.md
cat .gromozeka/prompts/agents-roster.knowledge.md
```

**2. Find the expert for your problem:**
- Build broken? → `build-release.role.md`
- Database issue? → `repository.role.md`
- AI integration? → `spring-ai.role.md` (AI Integration Agent)
- UI problem? → `ui.role.md`
- Architecture question? → `architect.role.md`

**3. Read that prompt** - it contains all their knowledge.

**4. Fix the issue.**

## Critical Build Command

After any fix, verify:
```bash
./gradlew :presentation:build :server:test -q || \
  ./gradlew :presentation:build :server:test
```

Pattern: `-q` first (saves tokens), full output only on error.

## Codex Run Actions

Codex App run buttons are configured in `.codex/environments/environment.toml`.
Keep common local commands there so both the user and future Codex agents use the same entry points.

Current high-value commands:
```bash
./gradlew :presentation:run -q
./gradlew :presentation:build -q
./gradlew :server:test -q
./gradlew :application:jvmTest --tests 'com.gromozeka.application.service.memory.MemoryMaintenancePipelineTest' -q
./gradlew :server:test --tests 'com.gromozeka.server.MemoryRealModelE2eTest' -Dgromozeka.memory.e2e=true -Dgromozeka.llm.cassette.mode=replay-only -q
```

Mongo is intentionally explicit:
```bash
GROMOZEKA_HOME="$PWD/dev-data/client/.gromozeka" docker compose -f "$PWD/server/src/main/resources/docker-compose.yml" up -d mongodb
docker compose -f "$PWD/server/src/main/resources/docker-compose.yml" stop mongodb
```

## Your Approach

1. User tells you what's broken
2. Read relevant agent prompts to understand that area
3. Fix the issue
4. Verify build passes

Agent prompts are your documentation.
