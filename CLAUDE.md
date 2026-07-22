# Emergency Repair Guide (Plan B)

**You are Claude, launched with this file because Gromozeka system is broken.**

Normal agent system isn't available. User will tell you what's broken. Your job: fix it.

## Understanding the System

Read `docs/development-guide.md` first. It describes the current modules,
runtime language, ownership boundaries, and verification commands. Runtime
Agent and Prompt configuration lives in the central Server and is not a
repository documentation mechanism.

## Quick Start Strategy

**1. Get context:**
```bash
cat docs/development-guide.md
```

**2. Read the relevant domain contracts and neighboring implementation.**

**3. Fix the issue.**

## Critical Build Command

After any fix, verify:
```bash
./gradlew :presentation:build :presentation:jvmTest --tests ApplicationContextTest -q || \
  ./gradlew :presentation:build :presentation:jvmTest --tests ApplicationContextTest
```

Pattern: `-q` first (saves tokens), full output only on error.

## Your Approach

1. User tells you what's broken
2. Read `docs/development-guide.md` and relevant domain contracts
3. Fix the issue
4. Verify build passes

Agent prompts are your documentation.
