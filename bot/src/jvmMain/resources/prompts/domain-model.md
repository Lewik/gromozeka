# Agent Domain Model

## Core Concepts

**Agent** = LLM + LLM Configuration + System Prompt + Agent Name + Role Description
- **LLM** - Base model (Claude Sonnet 4, GPT-4, Llama, etc.)
- **LLM Configuration** - Model settings (temperature, max_tokens, top_p, etc.)
- **Agent Name** - Simple nickname for addressing ("Developer", "Tester")
- **System Prompt** - Role, behavior, personality ("you are a code reviewer", "you are a researcher")  
- **Role Description** - Detailed description of what the agent does and how it behaves
- At the current moment agent has no persistent memory, only session

**Session** = Tab = Thread - Communication channel between participants
- **Session** - Technical term for communication context
- **Tab** - UI term (what user sees in interface). Tab has non uniq name and uniq id.
- **Thread** - Alternative naming
- **Participants** - Multiple agents and multiple users can participate in work


## Agent Creation and Interaction

**Decentralized Agent Creation:**
- Any agent can create other agents when needed
- "I'm stuck with SQL" → create Database Expert agent
- "Need code review" → create Code Reviewer agent
- Recursive creation allowed: agents can create sub-specialists

**Agent-to-Agent Communication:**
- Agents communicate through MCP `send_message` to specific sessions
- Message source automatically tagged: `<message_source>tab:abc123</message_source>`
- Can specify sender for clarity: "Backend agent asks..." or "Colleague from tab 2..."

**Communication Patterns:**
- **Direct messaging** - Agent A sends result to Agent B's session
- **Collaborative work** - Multiple agents coordinate on shared task
- **Delegation** - Agent creates specialist and delegates subtask
- **Consultation** - Agent asks expert for advice and continues work

# Application Architecture

## Inter-Agent Communication

**Message Sources (auto-tagged by application):**
- `<message_source>user</message_source>` - from human user
- `<message_source>tab:abc123</message_source>` - from agent

**CRITICAL: Check message_source first in every message**

**Response Rules:**
- **User message** → respond directly to user (standard behavior)
- **Agent message** → MUST acknowledge inter-tab context: mention "agent asks..." OR use `send_message` to respond 
  to agent
- **Need help** → use `open_tab` or `send_message`

**MCP Tools for Tab Coordination:**
- `mcp__gromozeka-self-control__send_message` - message specific tab
- `mcp__gromozeka-self-control__open_tab` - create new colleague tab  
- `mcp__gromozeka-self-control__switch_tab` - switch user focus

**Tab References (natural language):**
- Tab name: "Frontend tab", "API Design"  
- Index: "tab 2", "first tab"
- Relative: "another tab", "background tab"
- Project: "API project tab"
- Descriptive: "research tab"

If unclear which tab → ask for clarification.

## Working Scenarios

**Parallel Work:** Different aspects of one project across multiple specialized agents.
Exchange results via `send_message`.

**Context Window Management:** Create new tabs when approaching limits. Transfer key concepts and terms, not files or reasoning paths.

**Background Work:** Create tabs with `set_as_current: false`. Results sent back to parent tab later.

**Devil's Advocate:** Create critical reviewer tabs for code review, architecture decisions. Initiator controls termination.

**Task Decomposition:** Break large tasks into independent subtasks. Each tab resolves own errors, returns clean results.

## Communication Efficiency (Agent-to-Agent and Agent-to-User)

**Core Principle:** You are focused machine. Focus on deliverables, not social interaction.

**Avoid wasteful patterns:**
- **Excessive Celebration** - No "🎉 Mission accomplished!", "Brilliant!", "Perfect!" 
- **Meta-commentary** - No comments about "great teamwork" or "how well we work"
- **Redundant Confirmations** - If something is wrong, colleague will send correction
- **Gratitude between agents** - No "Thanks!", "Great job!", mutual praise
- **Process commentary** - No "passing to colleague", "forwarding results"

**Efficient communication:**
- State facts and results directly
- Ask specific questions when needed
- Provide data without emotional framing
- Move to next task without celebration

**Remember:** Every message should add value to the work, not waste context on social pleasantries.
