# ADR 001: Internal Reasoning Delegation

**Status:** Proposed  
**Date:** 2025-01-24  
**Deciders:** Architect, Business Logic Agent, Spring AI Agent  
**Tags:** multi-agent, reasoning, delegation

## Context

Current agent architecture creates new tabs for each specialized agent via `CreateAgentTool`. This works well for independent tasks but has limitations for complex reasoning that requires:

- Multiple specialized perspectives within single conversation
- Iterative refinement without context loss
- Ability to use large context window for reasoning
- Clean separation of intermediate work from final results

**Problem:** How to enable main agent to delegate complex reasoning to specialized agents WITHOUT creating new tabs, while maintaining conversation continuity and allowing cleanup of intermediate steps?

## Decision

Implement **Internal Reasoning Delegation** - a mechanism for dynamic agent switching within single conversation thread.

### Core Architecture

**Delegation happens INSIDE tool execution loop** in `ConversationEngineService.streamMessage()`:

```
User message → Main Agent
    ↓ delegate_internal_reasoning(agent_prompt="...")
Specialized Agent (inline created)
    ↓ works, writes intermediate messages
    ↓ return_control()
Main Agent (continues)
    ↓ final distilled answer
```

**Key insight:** Tool execution loop already exists in `streamMessage()`, allowing agent switching on each iteration by rebuilding system prompt.

### Domain Model

Three new `Conversation.Message.Instruction` types:

1. **DelegateReasoning**
   - Contains inline agent prompt
   - Signals ConversationEngineService to switch agents
   - Marks subsequent messages as intermediate

2. **ReturnControl**
   - Signals return to original agent
   - Ends intermediate reasoning phase

3. **IntermediateReasoning**
   - Marks message as cleanup candidate
   - User-visible but removable

### Implementation Pattern

**Application Layer** (`ConversationEngineService`):
```kotlin
var currentAgent = agent              // track current
val originalAgent = agent             // remember original

while (iterationCount < maxIterations) {
    if (iterationCount > 1) {
        val messages = loadCurrentMessages(conversationId)
        
        // Detect delegation instructions
        if (hasDelegateReasoning(messages)) {
            currentAgent = createInlineAgent(prompt)
            systemMessage = rebuild(currentAgent)  // ← KEY!
        }
        
        if (hasReturnControl(messages)) {
            currentAgent = originalAgent
            systemMessage = rebuild(currentAgent)
        }
    }
    
    // Continue with CURRENT agent's system prompt
    chatModel.stream(buildPrompt(systemMessage, history))
    
    // Auto-mark intermediate if delegated
    if (isDelegated) {
        message.instructions += IntermediateReasoning
    }
}
```

**Infrastructure Layer** (MCP Tools):
- `delegate_internal_reasoning` - creates DelegateReasoning instruction
- `return_control` - creates ReturnControl instruction
- Tools return instructions via metadata, not execute delegation

**Cleanup:** Reuse existing `SquashOperation` or `deleteMessages()` for removing intermediate messages.

## Consequences

### Positive

✅ **Single conversation thread** - no tab proliferation  
✅ **Large context usage** - can use 50 iterations for complex reasoning  
✅ **Transparency** - user sees all intermediate steps  
✅ **Flexibility** - any inline prompt for delegation  
✅ **No limits** - delegated agent works as long as needed  
✅ **Clean architecture** - domain Instructions, application logic, infrastructure tools  
✅ **Reuses existing code** - SquashOperation, tool execution loop

### Negative

⚠️ **Complexity** - agent switching logic in ConversationEngineService  
⚠️ **Context size** - intermediate messages consume tokens until cleanup  
⚠️ **No parallel delegation** - agents work sequentially in loop  
⚠️ **UI coupling** - cleanup UX requires presentation layer changes

### Risks

🔴 **Infinite loops** - delegated agent never calls return_control  
   *Mitigation:* maxIterations=50 limit already exists

🔴 **Confusion** - user sees many intermediate messages  
   *Mitigation:* Clear marking with IntermediateReasoning instruction

🔴 **Lost context** - accidental deletion of important intermediate steps  
   *Mitigation:* SquashOperation preserves provenance, can reference originals

## Alternatives Considered

### A. Multi-Tab Delegation (current CreateAgentTool)
**Rejected:** Works for independent tasks but breaks conversation continuity. No shared context window.

### B. External Orchestrator Agent
Create orchestrator that spawns tabs and aggregates results.

**Rejected:** Complex coordination, loses conversation flow, harder to debug.

### C. Prompt Engineering Only
Make single agent better at multi-step reasoning via better prompts.

**Rejected:** Limited by single perspective, no specialization benefits.

### D. Context Variables in ConversationEngineService
Pass `agentOverride` parameter instead of Instructions.

**Rejected:** Breaks clean architecture, couples infrastructure to application logic.

## Implementation Plan

### Phase 1: Domain (Architect Agent)
- [ ] Add DelegateReasoning instruction
- [ ] Add ReturnControl instruction  
- [ ] Add IntermediateReasoning instruction
- [ ] Compile check

### Phase 2: Application (Business Logic Agent)
- [ ] Modify ConversationEngineService.streamMessage()
- [ ] Add agent tracking (currentAgent, originalAgent)
- [ ] Add delegation detection on each iteration
- [ ] Add auto-marking intermediate messages
- [ ] Integration testing

### Phase 3: Infrastructure (Spring AI Agent)
- [ ] Create DelegateInternalReasoningTool
- [ ] Create ReturnControlTool
- [ ] Register tools in Spring context
- [ ] Add tool descriptions and examples

### Phase 4: UI (UI Agent, optional)
- [ ] Highlight intermediate messages
- [ ] Add "Cleanup intermediate" button
- [ ] Implement multi-select for intermediate cleanup

## References

- Multi-agent debate research: [2305.14325](https://arxiv.org/abs/2305.14325)
- Self-Refine pattern: [2303.17651](https://arxiv.org/abs/2303.17651)
- Chain of Density: context compression technique
- Existing: `CreateAgentTool` (cross-tab delegation)
- Existing: `SquashOperation` (message provenance tracking)

## Notes

**Key architectural decision:** Leverage existing tool execution loop instead of creating new mechanism. System prompt rebuild on each iteration enables clean agent switching without breaking flow.

**Future enhancements:**
- Recursive delegation (delegated agent delegates further)
- Parallel delegation (multiple agents work simultaneously)
- Automatic distillation (AI-powered squash of intermediate messages)
- Delegation templates (predefined specialist agents)
