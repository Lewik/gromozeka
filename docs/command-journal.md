# Command Journal

Command Journal is an optional sidecar audit trail for console work performed through Gromozeka.
It does not replace `Conversation`: the conversation remains the causal, provider-facing history used to
understand why an action happened. The journal records what was executed without adding synthetic
messages to model context.

Enable the journal with:

```properties
gromozeka.command-journal.enabled=true
```

When the property is absent or false, no journal repository is created and command dispatch follows the
existing path.

## Ubiquitous language

The code uses the following nested `CommandJournal` types consistently:

- **Entry** — one root console activity and its related calls. A model command tool call is a root Entry.
- **Call** — the root command/Python invocation or a nested MCP tool call made by its command-scoped SDK.
- **Event** — a durable lifecycle observation such as accepted, started, completed, failed, or cancelled. `occurredAt` preserves source-reported time, while Journal state follows Server append order.
- **Content** — deduplicated textual arguments, source, or output linked to a Call or Event.
- **Source** — trusted Harness provenance carried outside public tool arguments.

`Bundle` is deliberately not a domain term.

## Stage 1 contract

Stage 1 records:

- POSIX and Windows commands launched by `grz_execute_command`;
- inline Python launched by `grz_execute_python`;
- command lifecycle and exit status;
- retained terminal UTF-8 output with byte-range/truncation metadata;
- MCP tool calls made through the command-scoped SDK, including textual results;
- direct MCP command launches and durable async command launches.

Resolved secret values are never supplied to the journal. Tool input is omitted when the tool contract has
`logInput=false`, and binary tool results are omitted. Arbitrary subprocesses created inside shell or Python
code are not observable unless that code invokes Gromozeka through the SDK/MCP boundary.

## Journal tools

When the journal is enabled, `grz_command_journal_report` is preloaded. It records a command or Python
invocation explicitly reported by the caller without executing it. Such entries have origin
`REPORTED_BY_CALLER` and event kind `REPORTED`; they must never be presented as Worker-confirmed facts.
The report tool is idempotent for its trusted ToolCall identity and records exact submitted text within
bounded UTF-8 limits.

The following read tools remain on-demand and are discoverable through the ordinary tool catalog:

- `grz_command_journal_search` — bounded project search with exact metadata filters and literal text;
- `grz_command_journal_get` — entry metadata, bounded calls, and bounded lifecycle events;
- `grz_command_journal_read_content` — paged text with per-item character limits and truncation metadata;
- `grz_command_journal_stats` — logical project counts plus physical Journal/database sizes; owner-only.

Search and reads enforce current project access. Self-report requires current Conversation write access.
The entire contributor is absent when the journal property is disabled.

The journal tables intentionally have no foreign key to Conversation. Deleting a conversation therefore
does not silently delete its audit trail. Retention and size notifications, Visual handlers, internal command
reporting by non-command tools, heuristic safety analysis, embeddings, and semantic search remain separate
stages.
