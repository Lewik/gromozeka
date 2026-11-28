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
- command lifecycle and exit status when those observations reach the Server;
- retained terminal UTF-8 output with byte-range/truncation metadata;
- MCP tool calls made through the command-scoped SDK, including textual results;
- direct MCP command launches and durable async command launches.

Resolved secret maps are not passed directly to the journal; supported `secret_env` inputs retain their
`secret://` references. Textual command, tool, and caller-reported output is stored verbatim and may contain
sensitive values. Tool input is omitted when the tool contract has `logInput=false`, and binary tool results
are omitted. Arbitrary subprocesses created inside shell or Python code are not observable unless that code
invokes Gromozeka through the SDK/MCP boundary.

## Observation and failure semantics

Command Journal is an observational sidecar, not a live execution-state or recovery system. Each Event records a
fact received at an existing processing boundary. Event streams may therefore be incomplete: for example, an
`ACCEPTED` Event without a later terminal Event means only that no terminal observation was recorded. It does not
mean that the command is still running. `WorkerRequest` and `CommandTask` remain authoritative for current state.

The journal does not infer `FAILED` or `OUTCOME_UNKNOWN` from silence, and Stage 1 does not add retries, backfill,
or reconciliation to reconstruct missing observations. Such Events are appended only when the existing Runtime
has established that outcome.

Automatic Journal observations are always best effort. A Journal storage failure is logged and publishes a
deduplicated user notification when the actor is known, while the original operation and its actual result remain
unchanged. The sidecar never retries, replays, backfills, or blocks an external effect in an attempt to obtain a
complete audit trail.

The explicit self-report tool still fails if its atomic Journal write cannot be completed because recording the
report is the tool's only effect.

## Journal tools

When the journal is enabled, `grz_command_journal_report` is preloaded. It records a command or Python
invocation explicitly reported by the caller without executing it. Such entries have origin
`REPORTED_BY_CALLER` and event kind `REPORTED`; they must never be presented as Worker-confirmed facts.
The report tool is idempotent for its trusted ToolCall identity and records exact submitted text within
bounded UTF-8 limits.

The following read tools remain on-demand and are discoverable through the ordinary tool catalog:

- `grz_command_journal_search` — bounded project search with exact metadata filters;
- `grz_command_journal_get` — entry metadata, bounded calls, and bounded lifecycle events;
- `grz_command_journal_read_content` — paged text with a cursor that resumes within oversized content and preserves slice/range metadata;
- `grz_command_journal_stats` — logical project counts plus physical Journal/database sizes; owner-only.

Search and reads enforce current project access. Self-report requires current Conversation write access.
The entire contributor is absent when the journal property is disabled.

The journal tables intentionally have no foreign key to Conversation. Deleting a conversation therefore
does not silently delete its audit trail. Retention and size notifications, Visual handlers, internal command
reporting by non-command tools, heuristic safety analysis, indexed literal/trigram/vector search, embeddings,
and semantic ranking remain separate stages.
