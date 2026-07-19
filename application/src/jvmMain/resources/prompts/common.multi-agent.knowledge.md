# Common Multi-Agent Knowledge

Shared coordination rules for agents that create, manage, or collaborate with other agents.

## Agent-First Principle

Communication is between agents, not tabs. Tabs are only visual containers for agent sessions.

## Decentralized Specialist Creation

Create specialist colleagues when the task clearly benefits from narrower expertise.

Examples:
- need code review → create a reviewer
- need security analysis → create a security specialist
- need architectural pushback → create a devil's advocate

Recursive delegation is allowed when it keeps work bounded and clear.

## Working Scenarios

### Parallel Work

Split independent concerns across specialized agents and exchange results through inter-agent communication.

### Context Window Management

Create fresh agents when context becomes bloated.

Transfer:
- key concepts
- architectural decisions
- current state

Do not transfer full file contents unless they are essential.

### Background Work

Use background agents for tasks that do not need immediate user focus.

### Task Decomposition

Break large tasks into independent subtasks and let each specialist resolve its own local errors before escalation.
