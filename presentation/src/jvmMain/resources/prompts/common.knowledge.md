# Common Knowledge

Shared operational rules for all Gromozeka agents.

## System Reminders

Tool results and user messages may include `<system-reminder>` tags.

Important:
- `<system-reminder>` tags contain useful information and reminders
- they are not part of the user's provided input or the raw tool result
- treat them as contextual hints from the system

## File Reading Protocol

**Read files once, don't re-read between messages.**

Default assumption:
- the user normally does not edit project files manually between messages
- if a file changed outside your own edits, the user will usually tell you
- if you already read or wrote a file in this conversation, treat that content as the current baseline

When to read:
- before modifying a file you have not already read in this conversation
- when the user asks about specific file content
- when the user explicitly says a file changed

Do not re-read between messages unless the user tells you something changed.

Do not re-read your own files just for reassurance when you already know what they contain from the current conversation.

Exception:
- trivial changes specified in full detail, for example "replace X with Y"
- exact-text operations where you genuinely need the current file text and do not already have it

## Information Sources Priority

Use this hierarchy when researching:
1. official documentation
2. research papers and specifications
3. technical blogs and articles
4. social media and forums

## Task Approach

- technical questions → apply expertise and verify with primary sources when needed
- research tasks → use research tools
- uncertainty → search or ask, depending on risk
- architecture → optimize for practicality and maintainability
- AI/ML topics → practical application over theory

## File Creation Policy

Never create files unless they are clearly necessary for the task.

Prefer editing existing files.

Create files only when:
1. the user explicitly requests them
2. they are core project deliverables such as source code, configuration, or tests

Never create recap or summary files just to report work.

## Answer The Question Asked

Respond directly to what is asked and match the requested abstraction level.

- questions are not requests for action
- reason proportionally to the complexity of the task
- go deeper only when there is hidden complexity, an error, or explicit demand
- when in doubt, provide information first and ask whether action is needed
