# Implementation Plan: Internal Reasoning Delegation

**Status:** Ready for implementation  
**Date:** 2025-01-24  
**Main ADR:** [002-internal-reasoning-delegation](002-internal-reasoning-delegation.md)

## Overview

Enable main agent to delegate complex reasoning to specialized agents WITHIN same conversation thread, without creating new tabs.

**Key innovation:** Leverage existing tool execution loop in `ConversationEngineService` for dynamic agent switching.

## Architecture Documents

| Document | Agent | Module | Status |
|----------|-------|--------|--------|
| [002-internal-reasoning-delegation](002-internal-reasoning-delegation.md) | - | Coordination | ✅ Complete |
| [003-internal-reasoning-instructions](../domain/003-internal-reasoning-instructions.md) | Architect | `:domain` | ✅ Spec ready |
| Application layer spec | Business Logic | `:application` | ⏸️ Business Logic Agent to create |
| Infrastructure tools spec | Spring AI | `:infrastructure-ai` | ⏸️ Spring AI Agent to create |
| UI cleanup spec | UI | `:presentation` | ⏸️ UI Agent to create (optional) |

## Implementation Sequence

### Phase 1: Domain (Architect Agent)

**File:** `domain/src/commonMain/kotlin/com/gromozeka/bot/domain/model/Conversation.kt`

**Changes:**
- Add `Instruction.DelegateReasoning` (with agentPrompt)
- Add `Instruction.ReturnControl` (singleton)
- Add `Instruction.IntermediateReasoning` (singleton)

**Specification:** See [003-internal-reasoning-instructions](../domain/003-internal-reasoning-instructions.md)

**Verification:**
```bash
./gradlew :domain:compileKotlin -q || ./gradlew :domain:compileKotlin
```

**Dependencies:** None

**Time estimate:** 30 minutes

---

### Phase 2: Application (Business Logic Agent)

**File:** `application/src/jvmMain/kotlin/com/gromozeka/application/service/ConversationEngineService.kt`

**Changes:**
- Track agent state (originalAgent, isDelegated, delegatedPromptId)
- Detect delegation instructions on each iteration
- Rebuild system prompt when switching agents
- Auto-mark intermediate messages

**Specification:** Business Logic Agent to create ADR in `docs/adr/application/`

**Verification:**
```bash
./gradlew :application:compileKotlin -q || ./gradlew :application:compileKotlin
```

**Dependencies:** Phase 1 complete (needs new Instructions)

**Coordination needed:**
- Verify `PromptDomainService.createInlinePrompt()` exists
- Agree on instruction parsing format with Spring AI Agent

**Time estimate:** 2-3 hours

---

### Phase 3: Infrastructure (Spring AI Agent)

**Files:**
- `infrastructure-ai/src/jvmMain/kotlin/com/gromozeka/infrastructure/ai/mcp/tools/DelegateInternalReasoningTool.kt`
- `infrastructure-ai/src/jvmMain/kotlin/com/gromozeka/infrastructure/ai/mcp/tools/ReturnControlTool.kt`

**Changes:**
- Create `delegate_internal_reasoning` tool
- Create `return_control` tool
- Return Instructions via structured text protocol
- Register tools in Spring

**Specification:** Spring AI Agent to create ADR in `docs/adr/infrastructure/`

**Verification:**
```bash
./gradlew :infrastructure-ai:compileKotlin -q || ./gradlew :infrastructure-ai:compileKotlin
```

**Dependencies:** Phase 1 complete (needs new Instructions)

**Coordination needed:**
- Agree with Business Logic Agent on instruction parsing format
- Test tool registration and discovery

**Time estimate:** 1-2 hours

---

### Phase 4: Integration & Testing

**Responsibilities:** All agents involved

**Tasks:**
1. Business Logic Agent: Extract instructions from tool results
2. Manual test: full delegation flow
3. Edge case testing (return without delegate, nested delegation)
4. Performance check (context window usage)

**Test scenario:**
```
User: "Analyze architecture and suggest improvements"
Main Agent: calls delegate_internal_reasoning(agent_prompt="You are architecture analyst...")
[System switches agent on next iteration]
Specialist: analyzes, writes findings (marked intermediate)
Specialist: calls return_control()
[System switches back to main agent]
Main Agent: summarizes findings, provides recommendations
User: clicks "Cleanup intermediate"
[Only final summary remains]
```

**Time estimate:** 1-2 hours

---

### Phase 5: UI (UI Agent, optional)

**Files:** Presentation layer components

**Changes:**
- Visual indicator for intermediate messages
- "Select all intermediate" action
- Optional: AI-powered squash integration

**Specification:** UI Agent to create ADR in `docs/adr/presentation/`

**Dependencies:** Phases 1-4 complete (needs working delegation)

**Time estimate:** 1-2 hours

**Priority:** Low (users can manually select/delete already)

---

## Agent Responsibilities Summary

### Architect Agent
✅ **Phase 1 complete** - Domain spec ready
- Domain ADR created: `domain/003-internal-reasoning-instructions.md`
- Next: Implement domain changes (~50 lines)

### Business Logic Agent
⏸️ **Blocked by:** Architect Agent implementation
- Create ADR: `application/001-delegation-in-conversation-engine.md`
- Modify ConversationEngineService (~80 lines)
- Coordinate instruction parsing with Spring AI Agent

### Spring AI Agent
⏸️ **Blocked by:** Architect Agent implementation
- Create ADR: `infrastructure/001-delegation-mcp-tools.md`
- Create 2 MCP tools (~150 lines)
- Tool registration
- Coordinate instruction parsing with Business Logic Agent

### UI Agent
⏸️ **Blocked by:** All phases 1-3
- Create ADR: `presentation/001-cleanup-intermediate-ui.md` (optional)
- UI enhancements (~50 lines)
- Low priority

---

## Communication Protocol

### Instruction Passing Format

**Proposal (for Business Logic ↔ Spring AI coordination):**

Tools encode instructions in structured text format:
```
✓ [User-visible message]

INSTRUCTION:[TYPE]
[KEY]:[VALUE]
...
---
[Additional info]
```

**Example:**
```
✓ Internal reasoning delegation initiated

INSTRUCTION:DELEGATE_REASONING
AGENT_PROMPT_B64:WW91IGFyZS4uLg==
MARK_INTERMEDIATE:true
---
Specialist will take over on next iteration.
```

**Approval needed:** Both agents confirm this format works.

---

## Testing Checklist

### Unit Tests
- [ ] Domain: Instruction serialization/deserialization
- [ ] Domain: Instruction validation (blank agentPrompt → error)
- [ ] Infrastructure: Tool execution with valid/invalid inputs

### Integration Tests
- [ ] Full delegation flow (delegate → work → return)
- [ ] Intermediate marking works
- [ ] System prompt changes logged
- [ ] Messages persist with correct instructions

### Edge Cases
- [ ] Return without delegate (ignored gracefully)
- [ ] Delegate without return (maxIterations prevents infinite)
- [ ] Multiple delegations in sequence
- [ ] Nested delegation (limitation documented)

### Manual Tests
- [ ] User experience: delegation is transparent
- [ ] User experience: intermediate cleanup works
- [ ] Performance: context window doesn't explode
- [ ] Logging: agent switches visible in logs

---

## Rollback Plan

If implementation fails or issues discovered:

1. **Domain changes:** Can be left in (unused code, no harm)
2. **Application changes:** Revert ConversationEngineService to git HEAD
3. **Infrastructure changes:** Remove tool beans from Spring context
4. **UI changes:** Revert presentation layer

**No database migrations needed** - Instructions stored as JSON, backward compatible.

---

## Success Criteria

✅ Main agent can delegate to specialist via tool call  
✅ Specialist works in same conversation thread  
✅ System prompt switches correctly  
✅ Intermediate messages marked automatically  
✅ Return control works  
✅ User can cleanup intermediate messages  
✅ No new tabs created  
✅ No infinite loops  
✅ All tests pass  

---

## Questions & Blockers

### Open Questions

1. **PromptDomainService.createInlinePrompt() exists?**
   - Business Logic Agent to verify
   - Alternative: directly create Agent with inline Prompt

2. **Instruction parsing format agreed?**
   - Spring AI Agent + Business Logic Agent coordinate
   - Proposed: Structured text protocol
   - Document final format in both ADRs

3. **Nested delegation support in MVP?**
   - Current spec: single level only
   - Future enhancement: stack-based tracking

### Blockers

**Current:** Architect Agent implementation needed (Phase 1)

---

## Timeline Estimate

**Optimistic:** 1 day (4-6 hours active work)
- Phase 1: 30 min
- Phase 2: 2 hours
- Phase 3: 1 hour
- Phase 4: 1 hour
- Phase 5: optional

**Realistic:** 2-3 days (accounting for coordination, testing, fixes)

**Agent parallelization:** 
- Phases 2 & 3 can work in parallel after Phase 1
- Saves ~1 hour vs sequential

---

## Next Steps

1. **Architect Agent:** Implement Phase 1 (domain changes)
2. **Coordination:** Business Logic + Spring AI agree on instruction parsing format
3. **After Phase 1:** Business Logic & Spring AI create their ADRs and implement
4. **After Phases 2-3:** Integration testing
5. **UI Agent:** Start when ready (optional)

---

## References

- Main ADR: [002-internal-reasoning-delegation](002-internal-reasoning-delegation.md)
- Domain spec: [003-internal-reasoning-instructions](../domain/003-internal-reasoning-instructions.md)
- ConversationEngineService: `application/.../ConversationEngineService.kt`
- Existing CreateAgentTool: `infrastructure-ai/.../CreateAgentTool.kt`
- SquashOperation: Already implemented for message cleanup
