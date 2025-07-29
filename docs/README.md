# Claude Code Integration Documentation

This directory contains documentation on Claude Code CLI integration findings and implementation details.

## Contents

### Core Issues
- [processbuilder-hanging-issue.md](./processbuilder-hanging-issue.md) - Critical bug with `--print` flag in subprocess mode
- [session-management.md](./session-management.md) - How `--resume` actually works (spoiler: creates new sessions)
- [session-storage.md](./session-storage.md) - Where sessions are stored and file format details

### Implementation Guides  
- [streaming-integration.md](./streaming-integration.md) - How to integrate streaming mode properly

## Purpose

These documents capture the results of extensive research and debugging sessions to understand Claude Code's internal workings. This knowledge is essential for proper integration and can save significant time when troubleshooting.

## Quick Reference

### Critical Issues
- **Never use `--print` with ProcessBuilder** - it hangs indefinitely
- **Session IDs change on `--resume`** - don't hardcode them
- **Use pipe approach**: `bash -c "echo 'message' | claude ..."`

### Key Locations
- **Sessions**: `~/.claude/projects/[encoded-path]/`
- **Format**: JSONL (JSON Lines) 
- **Linking**: Sessions linked via `leafUuid` in summary objects

For detailed information, see the individual files above.