# Emergency Repair Guide (Plan B)

**You are Claude, launched with this file because Gromozeka system is broken.**

Normal agent system isn't available. User will tell you what's broken. Your job: fix it.

## Understanding the System

**Problem:** You don't know how Gromozeka works yet.

**Solution:** Read agent prompts - they contain all knowledge about the system.

## Where Agent Knowledge Lives

**Agent prompts = system documentation**

**Builtin prompts:**
```
presentation/src/jvmMain/resources/prompts/*.md
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
- AI integration? → `spring-ai.role.md`
- UI problem? → `ui.role.md`
- Architecture question? → `architect.role.md`

**3. Read that prompt** - it contains all their knowledge.

**4. Fix the issue.**

## Critical Build Command

After any fix, verify:
```bash
./gradlew :presentation:build :presentation:jvmTest --tests ApplicationContextTest -q || \
  ./gradlew :presentation:build :presentation:jvmTest --tests ApplicationContextTest
```

Pattern: `-q` first (saves tokens), full output only on error.

## Your Approach

1. User tells you what's broken
2. Read relevant agent prompts to understand that area
3. Fix the issue
4. Verify build passes

Agent prompts are your documentation.
