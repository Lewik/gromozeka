# Gromozeka Development Guide

This document is the repository-level entry point for development without a
running Gromozeka instance. AI connections, model specifications, runtime
assignments, Agents, and Prompts are managed by the central Server. Repository
resources are import templates, not a second live configuration source.

## Current Architecture

Gromozeka is a Kotlin Multiplatform application with Compose clients, a central
Server, and standalone Workers.

- `:shared` contains cross-cutting primitives and utilities.
- `:domain` contains technology-light models, repository contracts, service
  contracts, presentation contracts, and tool contracts.
- `:application` implements use cases, orchestration, and transactional
  workflows.
- `:infrastructure-db` implements PostgreSQL persistence.
- `:infrastructure-ai` contains provider integrations, MCP, memory, embeddings,
  and tool implementations.
- `:state-sync` provides storage- and transport-neutral invalidation, snapshot
  coalescing, shared loads, and stale-replica protection.
- `:remote-protocol` and `:remote-client` define the client-to-Server boundary.
- `:server` is the control plane and web endpoint.
- `:worker` is a trusted standalone executor.
- `:worker-runtime` contains the shared KMP Worker registration client and
  Gateway runtime: handshake, reconnect, heartbeat, request/response routing,
  cancellation, capability/tool publication, and the durable event outbox with
  bounded HTTP batch delivery. JVM/Spring lifecycle, TLS
  configuration, and tool implementations stay in `:worker`; mobile lifecycle
  and storage stay in `:mobile-worker`.
- `:presentation` contains the shared Compose clients and presentation state.
- `:presentation-android` packages the Android client application.
- `:mobile-worker` contains the shared mobile Worker runtime.
- `:mobile-worker-android` packages the Android mobile Worker application.

Dependencies should point toward domain contracts where practical. Framework,
storage, provider, and transport details stay in their infrastructure modules.
Presentation bootstrap may wire infrastructure, but ordinary UI code should use
domain and application abstractions.

Declarative client state is synchronized by revisions rather than transported
as mutation deltas. Application services publish affected resource keys after
their transaction commits. Clients conflate invalidations, pull the latest
revision, and then reuse the existing typed read request for the current
snapshot. Reconnect starts from a fresh snapshot; it does not replay missed
declarative mutations. Conversation messages and other ordered event streams
remain separate because they cannot be safely conflated.
The conversation timeline reads bounded pages over authenticated HTTP separately
from the command WebSocket. Pages use thread/position cursors and include an event
watermark; the client subscribes after that watermark and applies message events
incrementally. A replay gap resets the visible page instead of replaying an
unbounded backlog. Completion events do not reload history. Timeline previews omit
provider replay state and signatures; the full persisted messages remain available
for model context and explicit detail/edit reads. Older pages preserve scroll
anchors, and search loads a page around its target message.

Active model-call presentation is a separate cumulative state-sync snapshot.
It is intentionally transient: losing it must not affect model execution,
conversation history, cancellation, or terminal runtime events.

The main dogfood chat path is `OPEN_AI_SUBSCRIPTION`, implemented by
`:infrastructure-ai:openai-subscription`. Spring AI adapters remain responsible
for other providers, embeddings, and auxiliary integrations. Provider quirks
belong behind those infrastructure boundaries rather than in domain workflows.

Each finite model step returns an explicit `AiStepOutcome`: completion, external
tool requests, provider continuation, truncation, refusal, or failure. The Server
persists completed assistant blocks before executing tools. Provider continuation
also covers client-executed provider tools. Their messages carry the
`providerManagedTool` metadata flag and are excluded from application tool
routing. The adapter executes one persisted pending call in the next finite
step, persists its result through another continuation, and then resumes the
model. Cancellation and the conversation iteration limit still apply.

OpenAI Subscription models with `use_responses_lite=true` receive the `web.run`
function namespace and use the subscription `/alpha/search` endpoint. Ordinary
Responses models keep the hosted `web_search` tool. Both honor the connection's
web-search switch and the agent's native-tool policy. Search uses the same
connection, model, and conversation key as model execution; raw provider replay
is restricted to that connection/model. Reference IDs and text results survive
runtime restarts; encrypted search output is not added to visible history.

Subscription Responses Lite compaction is a separate finite model step. The
adapter checks the configured token threshold before inference, using a local
request estimate and the most recent persisted provider usage for the same
connection/model. It sends `compaction_trigger`, persists the returned opaque
`ContextCompactionResult` with bounded recent user text, and returns `CONTINUE`.
The next step replays that checkpoint instead of the covered history, including
after a Server restart. Compaction never dispatches tools. Ordinary Responses
models keep server-managed `context_management` compaction. Token estimates are
approximate; encrypted payloads and images must not be counted as base64 text.

Provider continuation enqueues another model step under the existing turn limit without inventing a
user message. Incomplete or refused batches never execute external tools.

Assistant remarks, readable reasoning, and opaque-reasoning placeholders are
displayed in received order. This is not token streaming. TTS still consumes only
the existing structured assistant speech fields, never reasoning. Native signed
blocks and provider phases are retained for same-provider replay; readable UI
content is not a replacement for opaque provider state.

Claude Code uses a locally validated JSON envelope, not native external tool use.
Its `tool_calls` branch contains ordered `content` entries: `tool_call` with
`action_name`/`arguments`, or `message` with the usual assistant payload. The
`final_answer` branch ends the turn. The complete assistant text and all action
arguments are validated before dispatch, not just the CLI's terminal text
projection. Completed content blocks may share a message ID; they must not be
deduplicated by that ID. Quoted tool syntax inside a valid answer is ordinary
text, never executable input. Signed thinking is retained separately, unchanged.
Tool results arrive in the next transcript step; remarks cannot stand in for
results. Copilot likewise keeps external execution in Gromozeka while collecting
completed SDK assistant events.

A rejected Claude response invalidates only its native session and process lease.
There is exactly one automatic retry, in a fresh native session rebuilt from the
canonical Gromozeka checkpoint and tail. Rejected text, thinking, corrections and
compaction data are not copied into that retry or accepted replay. A second
invalid response is a visible failure; no candidate actions are dispatched.
Diagnostic IDs, failure categories and response fingerprints identify the rejection
without logging its private text. Successful retry usage includes both attempts,
while context usage describes only the accepted session. This is protocol
validation, not a semantic truth detector for arbitrary prose inside valid JSON.

Claude Code compaction notifications and replay data have separate roles. A
`compact_boundary` is retained as message metadata; the persisted compaction
payload contains the synthetic summary and retained native messages, including
attachments. The Worker mirrors its CLI transcript in the session state so a
new process can continue tracking the retained tail. A fresh or forked session
replays the latest checkpoint and subsequent Gromozeka messages. An explicit
missing-native-session error also retries through that path. Apart from that
case and the bounded response-validation retry above, arbitrary provider failures
and cancellation never restart a call automatically.

Configured Claude Code compaction thresholds use the last measured context usage.
At or above the threshold, Gromozeka invokes the built-in `/compact` between model
steps in the same CLI session, before sending the next input. The CLI's native
auto-compaction remains active. Its `--autocompact` flag selects a window size,
not an exact threshold, so Gromozeka does not substitute that flag for the policy.
Safe mode disables customizations; built-in slash commands remain available for
this explicit control operation. User messages remain wrapped as transcript data.

Run the opt-in Claude Code recovery checks with an authenticated local CLI:
`GROMOZEKA_CLAUDE_COMPACTION_LIVE=true ./gradlew :infrastructure-ai:jvmTest --tests '*ClaudeCodeCompactionLiveTest' -q`.
They use synthetic Haiku conversations and verify both policy-triggered and
native automatic compaction, forks, and missing-session recovery.

Voice input owns its destination from the start of a recording or detected
phrase. PTT retains that target through microphone preparation and recognition;
continuous input carries a separate target with each queued phrase. Both use
`VoiceInputDelivery` for auto-send and composer insertion. Delivery must never
resolve the currently selected tab again or redirect a result when its target
has closed. Provider VAD forwards the speech-start `item_id` through the Server
so transcripts arriving out of order retain their original destinations.

## Personal Message Delivery

The authenticated User owns one delivery preference across conversations and clients:
`STEER` (default when unset) or `AFTER_CURRENT_TURN`. It is stored separately in
`user_message_delivery_preferences`, not in the legacy Server-wide `UserProfile`.
Get/set requests have no client-supplied user ID; StateSync invalidations use the
same authenticated user's scope. The setting applies only to newly submitted
human chat input, not peer requests/results, command completions, or other
runtime-owned ingress. Already accepted messages retain their placement.

Admission resolves placement under the conversation coordinator's lock. Steer
for the active agent uses the next safe boundary, including a model-only response
or a continuation gap, without cancelling a model/tool call or splitting a tool
call/result pair. Full-turn input remains queued across every continuation.
Idle input starts normally in either mode. Explicit input to another agent is
not injected into the current agent; broadcast input steers the active responder
while retaining queued responses from other selected agents. Response review's
stale-input guard and Stop/Interrupt controls remain authoritative.

## Interface Localization

`localization/en.json` is the canonical interface catalog. Keep semantic context
in `localization/context.json`, terminology in `localization/glossary.json`, and
generation guidance in `localization/translator-prompt.md`. Packages remain
ordinary JSON; update every bundled locale when adding or changing a message.
Use the active translation in UI helpers and structured `LocalizedText` for
messages resolved at display time. Preserve user content and raw diagnostics.

Personal translation packages and shared/per-client choices belong to the
authenticated User on the Server. Clients retain a bootstrap/offline display
cache. The nine `grz_translation_*` MCP tools expose source context, validation,
package management, selection, and synchronization to Agents. See
[interface translations](../localization/README.md) for the package format,
ownership contracts, and validation commands, and
[Web font fallback](localization-fonts.md) for bundled font resources.

## Corporate Compatibility

External integrations must respect the operator's selected provider policy. A
disabled or unconfigured provider must receive no requests, including discovery
calls, health probes, fallback traffic, embeddings, speech, or auxiliary tool
calls. Provider-specific integrations and optimizations are welcome, but they
must stay explicit, isolated, and operator-controlled rather than silently
bypassing deployment policy.

The optional [Telegram group channel](telegram-channel.md) is disabled by default
and intended only for explicitly enabled personal deployments. It is a Server
channel adapter using the conversation actor, not a Worker or a separate LLM loop.

Before adding a runtime or distributable dependency, verify that its license
permits the intended closed-source commercial use and distribution without
reciprocal source-disclosure obligations. Proprietary CLIs and services should
be installed, licensed, and authenticated separately unless their terms clearly
permit redistribution. Experimental adapters around separately installed tools
must be opt-in and identified as unsupported integration paths.

Network destinations, credential ownership, and relevant provider data
retention behavior should be visible to the operator. Do not claim privacy,
zero-data-retention, or compliance properties that are not guaranteed by the
selected provider and account contract. These dependency rules do not change
Gromozeka's own license: commercial use still requires the permission described
in `LICENSE`.

Standalone Server and Worker archives are self-contained. Release builds
download pinned official Temurin JRE and Node.js archives, verify their SHA-256
checksums, and preserve their legal notices inside the resulting packages. The
Server includes Java. The Worker includes Java, Node.js, and the pinned
Gromozeka Browser MCP package. Installed applications never select a system
runtime or download executable code during first launch. Docker images follow
the same runtime boundary. Claude Code, GitHub Copilot CLI, and browser binaries
are never bundled.

## Diagnostic Logging

Persistent runtime logs are size-bounded and rotated. Set `GROMOZEKA_LOG_DIR`
to redirect the complete installation to one base directory; Worker logs stay
under its `workers` child directory. JVM launches can use
`-Dgromozeka.log.dir=/path` or `--gromozeka.log.dir=/path` instead. Standard
Spring Boot `LOGGING_FILE_PATH`, `-Dlogging.file.path=/path`, and
`--logging.file.path=/path` remain exact per-process directory overrides.
Desktop client verbosity can be changed with `GROMOZEKA_LOG_LEVEL` or
`-Dgromozeka.log.level`; production defaults to `INFO` and development to
`DEBUG`.

Android and iOS clients and Mobile Workers keep approximately 3 MB of
diagnostic logs inside their application sandbox. Browser clients log only to
the browser console. Diagnostic logs must not include conversation text,
credentials, raw authorization headers, or exact device locations.

Markdown preparation has a presentation-only recovery boundary. Recoverable parser
and syntax-highlighter failures, including invalid AST or highlight offsets, fall
back to the original plain text. No stored messages or provider payloads are
rewritten. Highlighter spans are validated as a complete set before reaching
Compose; invalid ranges are rejected, not clamped. Each highlight job owns a fresh
builder and is keyed by source, language and theme, so cancelled/stale jobs cannot
replace newer text. Diagnostics retain stack locations and failure stage/length,
but omit exception messages that may contain source excerpts. Cancellation and
fatal errors still propagate; there is no blanket catch around composable calls.

## Runtime Language

- A **Project** is a logical working context.
- A **Conversation** belongs to a Project, is not bound to a Workspace, and has
  an explicit set of connected User and Agent participants. Project membership
  makes a User eligible to join; a connected User participant is required to
  access that Conversation.
- An **Agent** is a server-managed model, prompt, and behavior configuration. It
  is not an executor.
  Its typed [tool access policy](agent-tool-access.md) is independent of preloading
  and applies to ordinary tools and supported provider-native capabilities.
- A **Worker** is a named execution process. The Server is not a Worker.
- Workers have one resource model. Platform, advertised capabilities, ownership,
  and optional user-context binding are independent properties. A user-bound
  Worker can both report context and execute supported operations. Context
  ingestion requires a binding established during approved registration, not a
  particular platform or Worker kind.
- A **Workspace** is a logical Project resource. The only current kind is a
  filesystem tree.
- A **Workspace Mount** records that one Worker sees one Workspace at a
  Worker-local root path.

Worker-scoped operations select an exact Worker. Workspace-scoped operations,
including shell, filesystem, and Git tools, select an exact Workspace Mount.
The Server does not inspect a Worker's filesystem, guess a target, or reassign a
call. It may redeliver the same persisted request ID, never automatically repeat
an action with an uncertain outcome. See [Worker request delivery](worker-request-delivery.md).

Conversation turns and memory pipelines always run on the Server. A Worker can
execute configured tools and finite AI request-response operations, but it does
not own conversation or memory orchestration.

Posting a User message and explicitly invoking an Agent are separate operations.
The Server selects a Conversation's configured automatic responders when accepting
a User post, persists the message once, and runs those Agents sequentially through
the serialized Conversation runtime. Without automatic responders, posting only
appends the message. An explicit Agent invocation (including a UI `@mention`)
starts only that connected Agent, overriding automatic responders. Agent-authored
messages do not trigger automatic replies.

Creating a Conversation or changing its participants to exactly one User and one
Agent enables that Agent's automatic replies. Manual changes persist until an
actual participant-set change; adding participants preserves existing choices,
and disconnecting an Agent removes it from the automatic responders.

Every AI connection has an exact execution target: the Server or one named
Worker. Finite LLM calls, embeddings, speech transcription, and speech synthesis
use that target. Realtime and long-lived streaming AI sessions are Server-only.
Claude Code is the exception to general target selection: its connection always
targets a Worker where Claude Code is separately installed and authenticated.
GitHub Copilot can target the Server or one exact Worker. Gromozeka bundles the
MIT-licensed Java SDK, but the operator installs and licenses Copilot CLI
separately on the selected target. Server-targeted connections can use either
that Server's CLI login or an encrypted per-user GitHub token. Worker-targeted
connections use only that Worker's local CLI login; user tokens are never sent
to Workers. A connection never falls back between auth modes, execution targets,
Workers, or models.
A known offline target can receive a queued finite request within its delivery
TTL. Missing or incompatible targets fail explicitly; Gromozeka does not fall
back to another Worker or to the Server. Live audio stream operations still
require an online target and retain process-local identity checks.

Each Worker registration advertises a stable environment profile collected at
startup. The execution topology uses that profile without changing on every
heartbeat. `grz_get_worker_environment` recollects the complete profile and
volatile capacity, process, executable, and project-mount data on the selected
Worker when current facts are needed.

## Runtime Persistence

PostgreSQL stores the scheduling state, tool executions, memory operations,
commands, monitors, and monitor delivery state in separate JSONB columns.
Runtime transitions lock one conversation row and read and update only the
components they use. Unchanged operations do not rewrite the row.

Durable replay events and diagnostic traces occupy separate append-only rows,
retaining the latest 10,000 events and 2,000 traces per conversation. A transition
commits its state, counters, new journal entries, and scheduling notification
atomically. Existing cursors and payloads survive the storage migration.
Ordinary scheduling and command inventory reads never load these journals.
The UI snapshot reads its state and latest 200 traces from one consistent
PostgreSQL snapshot; targeted Telegram and history lookups select event metadata
or a single matching event.

Run the storage and migration checks against the checkout's PostgreSQL slot:

```bash
GROMOZEKA_POSTGRES_RUNTIME_TEST=true \
GROMOZEKA_POSTGRES_URL=jdbc:postgresql://localhost:<slot-postgres-port>/gromozeka \
./gradlew :infrastructure-db:jvmTest \
  --tests '*PostgresConversationRuntimeCoordinatorTest' \
  --tests '*CompactionCoverageMigrationTest' -q
```

## Development Slots

The Server maintains a personal per-user catalog of warmed development checkouts.
A Slot references an existing Workspace/Mount; preparation, full cloning, copying
and Git operations remain explicit agent work through ordinary tools. Registering
or retiring a Slot never creates or deletes a directory. Slot numbers are stable,
Runtime-wide and never reused; UUIDs remain internal identities.

A SlotLease belongs to a Conversation, not its Agent or internal history Thread.
One Conversation can hold several slots. History compaction/version changes keep
leases; forks do not inherit them. The existing Restart button creates a new
Conversation and is intentionally unchanged: the new conversation has no lease,
and the original occupation remains visible for explicit return or reclaim.
Closing a tab does not implicitly free its slots.

The initial modes are READ and WRITE: readers may coexist with one writer, without
snapshot guarantees. All acquisitions create durable SlotRequests and return
acceptance immediately, even for a free slot. The database-backed processor grants
access and writes a notification in the same short transaction. Notifications use
the existing durable Conversation runtime and safe delivery points, without shell
wait commands or model polling. Pending requests survive history changes; deletion
or loss of eligibility rejects pending requests. Already granted leases require
explicit return rather than timeout-based reclamation. The slot queue is separate
from resource-heavy command admission.

Command origin is selected automatically from its exact WorkspaceMount and the
calling Conversation's lease before Worker dispatch. The immutable slot/lease
marker is also stored with delayed Worker requests, shown in command results and
completion notifications, and retained after release. Workers inject
`GRZ_SLOT`, `GRZ_SLOT_ID` and `GRZ_SLOT_LEASE_ID` per child process. Server and
Workers are updated together; slot support is not negotiated through tool
contracts. Software chooses how to use the number for
ports/names; it is neither a port allocator nor a per-process unique identifier.
Existing checkout-specific `.env` development-port settings are not rewritten.

`grz_slot_release` identifies an exact lease. Best-effort read-only Git/activity
observations either allow ordinary return or retain the lease and issue a
15-minute confirmation token stored with it. Echoing that token releases as-is;
there is no force flag, byte-level snapshot requirement, automatic Git repair,
process termination or mandatory drain. Expiring a confirmation does not expire
the lease. Consumed receipts are safe to repeat and cannot release a successor.
Unreachable Workers and unavailable inspections are unknown, not clean.

The conversation toolbar opens the Slots inventory and distinguishes occupied
numbers from pending requests. The inventory shows locations, occupants and
request times. Exceptional reclaim can be requested by a tool, but only the
separate authenticated native-client confirmation operation completes it. The
confirmation is for one lease; the former Conversation is notified and its files
and processes are untouched. The model-facing tool has no approval boolean.

The opt-in `SlotLiveFlowTest` exercises actual OpenAI Subscription inference and
a separately managed real Worker, not cassette replay. It requires explicit
`GROMOZEKA_SLOT_LIVE_TEST=true`, a fresh `slot_live_*` PostgreSQL schema, an unused
fixture-state directory, the built Wasm web root, the assigned development port,
and an explicitly selected Codex auth file. The original auth file is never
modified. Public `ready.json` stages and a private `worker.yaml` coordinate the
fixture; use the generated configuration/home only for its temporary Worker.
The UI check uses the fixture's synthetic account and requires the native reclaim
confirmation. `GROMOZEKA_SLOT_LIVE_SKIP_HELD_PAUSE=true` skips only the intermediate
visual pause, not the final confirmation or backend assertions. The fixture drops
its own database schema and credentials; stop its separately managed Worker too.
Never point this test at an existing account/schema or publish raw fixture logs.

## Experimental Cross-Thread Collaboration

Enable only on an isolated Server with `GROMOZEKA_COLLABORATION_ENABLED=true`.
The default is off. Open two conversations in one project with identical tool
access policies and name them. Agents may use different AI connections, providers,
models and preloaded tool lists; each session uses its own configured runtime.
`grz_agent_sessions` discovers eligible endpoints; `grz_agent_request` submits
work without waiting; `grz_agent_reply` completes a request. `grz_agent_message`
is context-only and does not start a model turn. Results are queued automatically.
Runtime displays the requests, waits, and stored results. No UI tab is required
for server-side delivery. User input and safe-point steering remain available.

Text-only completion with open collaboration obligations is retained as a draft
and passed through a separate, tool-free check on the selected provider. The
check chooses human text, request dispositions and CONTINUE/ASK_USER/WAIT/COMPLETE.
It has one format correction at most. Runtime validates request IDs and wait
handles, preserves native generated content for provider replay, and records
actual delivery in a durable outbox. A stale decision cannot commit over queued
safe-point input. Failed or interrupted reviews retain their draft; they do not
silently publish it or rerun the original model call.

This first iteration deliberately rejects cross-project communication, mismatched
tool access policies and history-branch changes. Telegram and other externally bound
conversations keep their ordinary turn lifecycle: no collaboration prompt or response
review, and collaboration tools are hidden from both the catalog and tool discovery.
Direct collaboration calls from those channels remain rejected.
Completed peer results are not human TTS. Read-only delegation constraints are copied into the recipient context;
ordinary tool execution still uses the existing approval/policy mechanisms.
The experiment does not enable external protocols, create agents, or move
workspace files between sessions. Do not enable it in production yet.

## Tool Output Storage

Tool results retain their original bytes as managed Artifacts. Text responses are
stored as UTF-8; binary responses are never decoded before storage. The Server
sends a bounded text preview to the model only after strict UTF-8 decoding.
Invisible control characters such as NUL are displayed as literal `\u0000`;
invalid UTF-8 produces an explicit unavailable-preview message. Valid UTF-8 alone
does not prove that data is meaningful prose.

Conversation search indexes the complete decodable tool text, including content
past the preview limit. Existing textual tool results are backfilled by a data
migration. Image and PDF results keep their native provider representation.

`grz_save_tool_output` downloads an artifact into the selected Workspace Mount
in bounded chunks. `mode=original` preserves exact bytes. `mode=text` strictly
decodes `source_encoding` and writes UTF-8. Both modes validate the download before
publishing the destination; an existing file requires `overwrite=true`.
Artifact access is checked against its Conversation and the Worker's Project access.

A command result artifact identifies the returned byte range, not necessarily the
whole log. Commands retain the full merged stream in their Worker-local
`output_file`; the Server retains bounded terminal tails and delivered chunks.
These runtime tails use a base64 binary representation inside JSONB, including
NUL and invalid UTF-8. Migrations preserve existing stored text without resetting
the database; bytes already lost by older text decoding cannot be reconstructed.

`grz_send_command_input` writes bounded UTF-8 text to the stdin of a running
command, routed by its task ID to the owning Worker/mount. It adds no newline;
line-oriented programs require an explicit `\n`. `close_input=true` sends EOF
without cancelling the process. Success confirms pipe I/O, not processing by
the child. Failed or cancelled writes can have partial effects and are never
retried automatically. Input cannot reconnect after a Worker restart loses the
original pipe; a surviving command remains readable/cancellable but rejects stdin.
Writes are serialized independently of lifecycle control, so blocked stdin does
not prevent command cancellation. The tool never echoes input in its result.

### Native Visuals

`grz_visual` is a Server-native conversation tool with `reference`, `create`,
`list`, `get`, `update`, `highlight`, and `close` actions. `reference` is the authoritative
small markup vocabulary. Visuals are tabs beside Runtime in its existing side
panel, shared by conversation participants and rendered by common Compose code,
not HTML/WebView. KXML is pinned as a multiplatform dependency. Visual actions and
selects use the application's tonal `CompactButton`, text/number/textarea inputs
use `CompactTextField`, and spacing/control density come from `GromozekaTheme`.
They inherit theme shapes, typography and 40/48 dp compact/touch minimum heights;
markup does not introduce a separate design system. Explicit palette colors and
textarea row counts remain supported.

Document spacing is renderer-owned. Every standalone text block or native control
has the same exterior half-spacing h (currently 4 dp). The outer document wrapper
adds h vertically and h + 4 dp horizontally. Adjacent content blocks have 2h between
them; document edges have 2h vertically and 2h + 4 dp horizontally. Layout containers have zero
padding and zero gap, including nested divs and grid cells. Inline text fragments
share one rendered text block. Borders/backgrounds only paint and never change
spacing; adjacent framed cells may touch. Native control interiors and host chrome
keep their own component metrics. Markup has no padding/gap/margin controls. The
host does not add a second full content inset around the document.

The Server owns the accepted `{form, data}` snapshot. Input edits are local until
a button submits `{visual-id, event-id, button-id, state}`. Only submitted `form`
is accepted; incoming `data` is context for the handler, never a write-back.
Action receipts reserve an event before external effects and prevent duplicate
stdin/LLM delivery. Uncertain actions are not automatically replayed. LLM-handled
clicks are persisted as USER messages with typed `visual_interaction` content,
not JSON disguised as typed chat text. The event captures the Visual title,
button label/IDs, document revision and full submitted snapshot. Chat shows a
compact action card with expandable read-only event data. Opening details does
not re-submit a form; action messages cannot be edited as utterances. Provider
adapters render the event explicitly as a user-side UI action with its full
snapshot, escaping envelope delimiters. Script stdin keeps its ordinary JSON
contract. Application snapshots are not ingested as user-authored memory claims.
A form revision and the originating button event ID preserve whole-form write
intent through coalesced snapshots. Data-only updates never erase local drafts;
an explicit form replacement resets every field, even when saved values are
unchanged, and removes omitted members. Acknowledgements of a client's own click
do not undo typing performed after that click. Neutral,
noninteractive Material `BadgedBox`/`Badge` overlays mark each dirty Visual tab
and each changed input, textarea, select, checkbox or slider. The top-end dots
have no tooltip and stay inside their anchors without changing layout bounds.
Each field is compared independently with its accepted server value; the tab
shows the aggregate form state. Clicking does not clear indicators prematurely,
and reverting values clears them without submitting. Number editor drafts are
compared as numbers without conflating numeric and string select options.

`highlight` is a one-shot presentation command, not a state update. It targets up
to 16 currently rendered explicit element IDs, including containers and inline
text; a new set replaces the previous one and `[]` clears it. The Server validates
IDs against the current document and delivers a revision-bound directive to the
acting user's connected clients, without persistence or offline replay. Clients
keep the set locally across tab switches. Targets and the entire rectangular tab
(including its padding and close-button area) get a soft white glow and an 18 dp
arrow badge pointing into the target. Native controls and their glow share the
same Shape from the theme; both the shadow and its interior cutout use that shape.
The tab and plain containers keep their rectangular outline. A soft inset halo
also keeps the tab's top/bottom edges visible within the native tab row's clipping,
without adding layout padding or moving its selection indicator. No additional border,
background fill, text recoloring or animation is applied; ordinary component
borders and the selected-tab indicator stay unchanged. Clicking any target or tab
arrow clears the whole set on that client only, without activating a button or
link underneath, selecting/closing the tab, or submitting/resetting local drafts.
Duplicate directives are ignored, document replacement invalidates stale sets,
and client reload clears them. Neither snapshots, handler input, `get`, reminders,
nor dismissal acknowledgements carry highlight state. Dirty-form dots remain
separate noninteractive indicators.

Before each conversation LLM request, `VisualRequestEnricher` adds saved
`form + data` observations for all current Visuals. Updates coalesce between
requests and do not trigger model work. Full snapshots are sent when state changes;
short unchanged markers refer to full snapshots reconstructed earlier in the same
request. Canonical JSON hashing ignores object-key order and transport revisions.
The request-only replay cache is bounded and isolated by conversation, thread,
agent, actor and model. It anchors observations to retained USER/tool-result
messages without changing persisted history or introducing synthetic message IDs.
Compacted, truncated or edited anchors invalidate dependent short markers. Cache
loss, branch changes and Server restarts safely produce fresh full snapshots.
Assistant-only continuations get a full live block instead of modifying signed
assistant content. Channel history filtering precedes this late enrichment.
JSON values are untrusted data and XML delimiters are escaped; neither reminders
nor client drafts implicitly submit forms. `get` remains useful for autonomous
fresh reads, markup and diagnostics.

`create` requires both complete state sections, `form` and `data`. `update.state`
contains one or both complete sections: omitted roots remain unchanged, supplied
roots replace their entire previous objects. Members omitted inside a supplied
section disappear at every depth. An empty section clears it if schema and
bindings permit; an empty update with no sections is rejected. The same rules
apply to handler output. The resulting full state is validated atomically before
publication. Send `document` only for structural/control/schema changes, not for
ordinary content updates. Model-facing create/update results contain compact
acknowledgements (ID, revisions, status, handler task and diagnostics), never HTML
or state. Only `get` returns the full document and saved state; `list` is compact.
Schema or rendering errors leave the last valid state intact. Runtime diagnostics
are shown in host-owned panel chrome outside the document and are available via
`get`; user markup cannot hide this footer.

An optional handler is an owned ordinary managed command on one explicit mount.
Its `CommandTask.visualId` records the role. A separate Worker reader consumes
its retained merged output with a byte cursor and bounded NDJSON framing; it is
not a command monitor. Gateway retries of output are idempotent by generation
and byte cursor. The command receives button JSON on stdin and emits complete
root-section updates,
flushing one JSON object per line. Commands never receive every keystroke.
Closing a visual, replacing its handler, or explicitly cancelling its command
cancels the old process. A conversation response interrupt cancels ordinary
commands but excludes Visual-owned handlers, even when no model turn is active.
Both the in-memory and PostgreSQL coordinators enforce this boundary; explicit
single-task cancellation and Worker shutdown still stop handlers.
Worker session changes stop the visual without restarting the handler. Temporary
gateway disconnects or Server restarts do not kill a handler in the same Worker
process; only a changed session or a terminal command proves it has stopped.
The panel may retain a stopped snapshot so the failure remains visible.

Handler command secret references use private environment substitution at launch;
markup and state references remain literal and are never expanded into UI data.
The protocol uses separate authenticated Worker operations for handler control
and output, with exact session/mount/conversation ownership checks. These
operations require matching Worker Gateway protocol versions.

For interactive preview, run the test Server and Worker under a separate process
supervisor, not as long-running managed commands of the authoring conversation:
interrupting that conversation legitimately cancels its ordinary command tasks.
On macOS, manually bootstrapped user `launchd` services can live outside the chat
without login-time autostart or automatic crash restart. Stop them explicitly
with `launchctl bootout gui/<uid>/<label>` when the preview is no longer needed.
Keep local preview credentials, service definitions and logs outside version control.

Command state synchronization retries temporary connection/database failures with
backoff. A permanent rejected write is exposed as `synchronization_error` in the
Worker-local result and logged; it stops write retries, preserves output files,
and never reruns the command. Cancellation reads remain independent. After fixing
the cause, Worker startup reconciles the saved process outcome with the Server.

## Computer Use

Computer Use is pixel-based control of one exact Worker's real interactive
desktop. Prefer Browser Use when DOM and accessibility state are available;
Computer Use is the intrusive fallback for native applications, remote desktop
content, OS dialogs, and other surfaces exposed only as pixels.

The model uses three synchronous tools:

1. `grz_computer_targets` lists displays on the selected Worker.
2. `grz_computer_observe` returns a PNG and a short opaque `observation_ref`
   for that screenshot's coordinate frame.
3. `grz_computer_act` applies one bounded ordered action list exactly once and
   returns a fresh screenshot.

Computer Use has no durable session or reconciliation state. Each request is a
plain Worker-targeted tool execution. A cryptographically random process-local
reference resolves to immutable coordinate geometry, expires after five
minutes, and is consumed by one action request. It is valid only for the exact
Worker process that captured it, but it does not claim that the visible desktop
has remained unchanged. Calls on the same display are serialized only while
they execute.

Desktop actions are never repeated or reassigned automatically. A Gateway
disconnect leaves execution running and its result is saved until Server
acknowledgement. A Worker process crash after execution starts but before the
result is saved produces `OUTCOME_UNKNOWN`; the model must observe again before
deciding what to do. Delivery TTL limits when an action may start, independently
of its execution timeout and the caller's wait. Cancelling the turn persists a
request-scoped cancellation, delivered on reconnect if necessary; the backend
checks it between actions and releases any pressed keys or mouse buttons in a
`finally` block. Cancellation and execution timeout can leave partial effects.

Screenshots are ordinary tool-result Artifacts. Only the three latest Computer
Use screenshots are materialized into an LLM request; older images remain
durable but become compact text placeholders in provider context. Clients
communicate only with the Server and never connect directly to a Worker.

The current JVM backend supports interactive macOS, Windows, and X11 sessions.
Headless and Wayland Workers omit the Computer Use tools. macOS Workers query
Screen Recording and Accessibility before advertising the tools and again
before each request. The standalone macOS LaunchAgent uses a stable native app
launcher installed and signed only once, so these permissions do not attach to
a versioned shell or JRE and survive Worker updates.
Computer Use intentionally controls the real pointer, keyboard, focus, and
clipboard; there is no separate ownership or takeover UI.

Desktop Clients do not bundle or manage a Worker. Client and Worker are separate
applications with independent release archives, configuration, and lifecycle.
Workers always connect through the standard Worker Gateway. On macOS, the
standalone Worker can use its per-user LaunchAgent and stable helper under
Application Support so Screen Recording, Accessibility, and microphone consent
survive Worker updates. The Windows standalone Worker also supports an automatic
LocalSystem service. In that mode, only the main process connects to the Gateway;
an internal helper controls the active console user's unlocked desktop over a
local named pipe. The helper uses that user's token and environment, receives no
Worker credential, and belongs to a kill-on-close Windows job. The pipe checks
both endpoint process IDs. The service's command/file tools retain LocalSystem
permissions. Helper generations are embedded in opaque display IDs, invalidating
observations across helper restarts and Windows logon changes without changing
the Worker protocol. A missing interactive desktop is a transient tool error,
not a reason to omit Computer Use from the service's advertised tool catalog.
The full-desktop `grz_capture_screenshot` tool uses that same helper in service
mode, capturing all monitors in the user session instead of the service desktop.
On ordinary Workers, screenshot capture requires Screen Recording on macOS but
does not require Accessibility permission or the COMPUTER_USE capability.
Lock screens, UAC secure desktops, and arbitrary RDP-session selection remain
unsupported. See the [Worker distribution guide](../deploy/distribution/WORKER_README.md)
for service installation, updates, permissions, and credential ownership.
Ordinary Windows launches still control their own interactive session directly. Browser Bridge
and Claude Code remain separately installed user tools.

Deployments may attach a human-facing interactive desktop to a Worker. When
configured, `grz_worker_interactive_access_get` returns a stable Server URL;
opening it checks the current Gromozeka session and Worker access before issuing
a short-lived, one-time handoff to the deployment's desktop transport. Transport
details such as Amazon DCV remain optional Server/deployment adapters rather
than part of the generic Worker protocol.

## Identity And Authentication

The Server owns user identity. One stable User can have multiple typed identities;
local login is optional, and password credentials are stored separately. The
`loginAllowed` and `aiAllowed` flags independently control sign-in and AI access.
An observed Telegram identity creates a User with both flags disabled and no
project grants. Observation does not authenticate that person, enable Telegram
sign-in, or automatically link them to a similarly named account. Future OAuth or
OIDC login must attach to the same User instead of creating a parallel account model.

One Server deployment is one isolated Gromozeka Runtime. A Runtime can contain
multiple Users, Projects, and Workers, but it does not contain several pooled
customer organizations. A future managed control plane may provision several
Runtimes for one account or organization; each Runtime still owns its own
database, queues, secrets, users, and workers. On-premises installations use
the same Runtime shape without the managed control plane.

Public registration is closed. An empty Server prints a one-time first-owner
bootstrap token to its log. Clients use that token once to create the first
User, after which ordinary login issues an opaque, revocable Server session.
Only a hash of each session token is persisted. Local passwords are stored as
Argon2id hashes, and repeated failed logins are rate-limited.

Browser clients use an HttpOnly, SameSite session cookie. Secure cookies are
enabled by default when the Server listens on a non-loopback address and can be
overridden explicitly with `GROMOZEKA_AUTH_SECURE_COOKIE=true|false`.

Runtime Owners can inspect an append-only audit trail of successful identity
and access changes. Audit events contain typed actor, target, project, and
non-secret change metadata. They never contain passwords, raw access or
enrollment tokens, prompts, tool payloads, or conversation content. Login
attempts and ordinary activity remain operational logs rather than durable
security-audit events.

## Memory Banks

A `MemoryNamespace` is an internal memory-bank boundary selected by trusted
runtime context, not by model or client input.

- Conversation memory uses the Conversation Project bank:
  `project:<project-id>`. Existing Project permissions govern access.
- External memory MCP uses the authenticated User's personal bank:
  `user:<user-id>`.
- Tool arguments and hidden MCP context cannot select or override a bank.
- Run status, queue status, maintenance, embeddings, reads, and writes remain
  inside the same selected bank.
- `global` is reserved for explicit tests and benchmarks. Production code must
  never fall back to it implicitly.

Adding shared or cross-project memory later requires an explicit grant model.
Do not infer access from a supplied namespace string.

## Development Model

Typed domain contracts and KDoc are the primary coordination mechanism. Read
the relevant model and service contract before changing an implementation.
Use current source code as final truth when documentation has drifted.

Prefer interfaces, typed identifiers, sealed hierarchies, and enums over
stringly typed coordination. Use nullable values when absence is normal,
exceptions for violated invariants, and explicit result types when callers need
to distinguish several meaningful outcomes. Application services own workflows;
repositories should not make business decisions.

Layer ownership for focused work:

| Concern | Primary module |
| --- | --- |
| Domain design and contracts | `:domain` |
| Use cases and orchestration | `:application` |
| PostgreSQL persistence | `:infrastructure-db` |
| AI providers, live external MCP clients, memory, tools | `:infrastructure-ai` |
| External MCP definitions and accepted tool snapshots | Server database through `:infrastructure-db` |
| Immutable AI tool contracts and stable model-facing names | Server database through `:infrastructure-db` |
| Durable runtime scheduling | `:application` and `:infrastructure-db` |
| Durable Worker requests and event delivery | `:worker-runtime`, `:server`, `:remote-protocol`, and platform storage |
| Compose UI and presentation state | `:presentation` |
| Android client packaging | `:presentation-android` |
| Mobile Worker runtime | `:mobile-worker` |
| Android mobile Worker packaging | `:mobile-worker-android` |
| Server endpoints and composition | `:server` |
| Worker process and local execution | `:worker` |

Every active Server, Worker, or external MCP tool resolves through the
`ai_tool_contracts` registry. Its fingerprint includes the full definition and
runtime metadata, including documentation. Equal contracts share one
model-facing name across compatible executors; different contracts coexist as
stable versioned names. Runtime routing translates that model-facing name back
to the executor's original tool name without changing the tool argument body.

Repository dependency sources may be cloned into `.sources/` when exact
third-party behavior matters. They are research material and stay gitignored.

## Runtime Configuration Design

AI configuration, runtime Agents, and Prompts are Server-managed database
entities. Bundled definitions are application templates used to initialize an
empty catalog or prefill an explicit create/import flow. Updating a bundled
resource never silently changes an existing runtime entity.

- Keep stable cross-project behavior in global Prompts and Agents.
- Keep project-specific behavior in project-scoped Prompts and Agents.
- Keep each prompt focused on one class of information instead of repeating
  mutable facts across a stack.
- Put dynamic execution environment data in the runtime environment context,
  not in static prompt definitions.
- Change prompts in response to observed behavior and validate the assembled
  stack rather than only checking individual fragments.
- Treat Agent Skills as imported project-scoped packages. Prefer
  `grz_skill_export_to_directory` and `grz_skill_import_from_directory` for
  model-driven editing so package bytes stay outside model context. Inline
  import/export is for small integrations and carries text and base64 binaries
  through model context.
- Agent Skill import derives a workspace materialization plan. Runtime opening
  exposes instructions, a compact resource index, and an
  immutable `skill_id` plus `content_hash` handle. Model-readable resources are
  fetched only on demand through that exact handle, and binary resources are
  never copied into model context.
- `grz_skill_materialize` replaces the stable runtime package at
  `<workspace>/.gromozeka/skills/<name>` on the selected Worker
  mount. It accepts the same immutable handle returned by `grz_skill_activate`;
  materialization does not execute files, install dependencies, or grant
  permissions. It is effectively read-only because it is managed runtime setup
  and may be required for otherwise read-only operations. Behavioral `Readonly`
  mode still forbids editing that copy, the Skill package, or project files.

## Repository Checkouts

The usual local checkouts are:

```text
gromozeka/
|-- dev/      primary development checkout, branch main
|-- beta/     pre-release dogfood checkout, branch beta
`-- release/  stable checkout, branch release
```

Develop and verify in `dev/`. Synchronize other checkouts through Git instead
of copying files manually.

## Release Versioning

Published releases follow Semantic Versioning as `MAJOR.MINOR.PATCH`.

- `PATCH` contains fixes, optimizations, and technical changes without new features.
- `MINOR` adds functionality and resets patch to zero.
- `MAJOR` covers incompatible changes under normal SemVer rules and requires
  Lev's separate explicit approval. A general request to release does not grant it.

Choose the bump from **all changes since the last published release**. The
workflow's `patch` default is a technical default, not a versioning decision.

That public surface includes documented Server APIs, Client and Worker
protocols, configuration formats, and other contracts consumed outside the
implementing component. Normally a newer Server must accept older Clients and
Workers from the same major version. Lev may explicitly approve a per-release
exception, such as a Worker protocol break in a minor release. Explain the
incompatibility and required component updates; previous exceptions are not
blanket permission. A newer Client or Worker may use functionality that an
older Server does not provide, so compatibility in that direction is not
guaranteed.

Internal refactoring and database schema changes do not require a major version
when existing persisted data is migrated forward automatically without loss.
Breaking an external contract normally requires a major version even when the
code change itself is small, subject only to the explicit exception above.

All artifacts produced by one release carry the same product version. This
policy applies beginning with `1.7.0`; earlier releases are not retroactively
reclassified.

The release workflow resolves versions from remote GitHub tags at the first
metadata step. Manual published releases and generated versions must be greater
than the latest remote `v*` SemVer tag before any expensive verification or
packaging job starts. Prefer leaving the manual version field empty and
selecting `patch`, `minor`, or `major`; the workflow generates the next SemVer
version from the latest stable remote tag. Release tags use `vX.Y.Z`. A reserved
tag remains occupied even if a build or publication fails: never delete or move
it to reuse its number. Retrying failed jobs for the same release is allowed;
a replacement release must account for every reserved remote version.

## Named Secrets And Background Continuations

A background command or monitor keeps the identity of the user who created it,
not whoever last spoke in the conversation. The Server records this provenance
from its durable Worker request before accepting the result, including replies
received after the original caller stopped waiting. Bindings are immutable and
survive Server restarts. Workers cannot supply or change the initiating user.

Completion batches are grouped by both agent and user. Before another model call,
the Server checks current user/AI status, conversation membership and project
write permission. An older activity without trusted provenance still delivers
its output, but cannot start an anonymous or guessed-user continuation.

Public command metadata preserves the original `secret://name` references.
Substituted shell text and its generated environment are execution-only; copying
a displayed command must generate fresh variables, never reuse a past process's
environment names. Visual handler launches follow the same rule. The public
command preservation runs on the Worker, so update Workers to obtain that fix.
The ownership registry is Server-private and does not change the Worker protocol.
Explicit secret revelation remains a separate, confirmed, one-request operation.

## CI and Release Workflow

Application CI is manual: `.github/workflows/release.yml` is the single entry
point for verification, packaging and optional publication. Pushes, PRs and
tags do not trigger it. For a build-only run:

```bash
gh workflow run release.yml --ref main \
  -f publish_release=false -f deploy_aws=false -f skip_ios=true
```

Build-only mode creates Actions artifacts, not release tags, registry images
or GitHub Releases. iOS checks are skipped by default; opt in explicitly with
`skip_ios=false`. `skip_android=true` omits Android checks and APK publication;
JVM client verification still runs. Android remains enabled by default.
Windows service and Linux/Windows command-process checks run
through the reusable Worker workflow; translation/font validation and Compose
E2E run inside the main workflow. JNI library builds remain separately manual.

Release JARs and Browser MCP are prepared once. Server/Worker archives are
packaged in a three-platform matrix, reusing the shared JARs and production Web
assets. Only the Server has a Docker image; its OCI build runs alongside the
remaining checks. Publication waits for **all** checks and artifact builds,
reserves the tag, and copies that same image without rebuilding. Packaging and
OCI jobs use `ubuntu-24.04` hosted runners with preinstalled Brotli/Skopeo; do not
run a blanket APT update/install in these jobs. Tool checks and OCI copies have
explicit step/command timeouts and visible progress. Publication must be
dispatched from `main`. Deploy is a separate, explicit opt-in; the
manual/reusable deployment workflow can also install an existing release.

Linux CI jobs pull PostgreSQL, Java base images, and BuildKit explicitly from
Google's `mirror.gcr.io` Docker Hub cache. PostgreSQL and the Server Java runtime
retain their pinned digests. Runtime-check PostgreSQL starts in a bounded job
step. E2E image environment overrides and the Server Dockerfile build argument
keep the existing Docker Hub defaults for local builds and deployments; no
Docker daemon settings are changed.

Validate workflow contracts locally with an isolated Python environment:

```bash
python3 -m venv build/ci-checks
build/ci-checks/bin/pip install -r scripts/requirements-ci.txt
build/ci-checks/bin/python scripts/test-release-workflows.py
```

## Verification

Default to the cheapest check that covers the changed boundary:

```bash
./gradlew :<module>:assemble -q
./gradlew :<module>:compileKotlin<Target> -q
./gradlew :<module>:test --tests '<focused test>' -q
```

Live assistant-progress checks are opt-in. Claude Code uses its existing CLI login:

```bash
GROMOZEKA_CLAUDE_CODE_REAL=true GROMOZEKA_CLAUDE_CODE_MODEL=haiku ./gradlew :infrastructure-ai:jvmTest --tests '*ClaudeCodeCliRuntimeTest.realClaudeCodeReturnsWrapperToolCallWhenEnabled' --rerun -q
```

The subscription check covers a remark, an external tool result, a structured
final answer, and history replay on a fresh connection. It reads an explicitly
selected Codex auth file without refreshing or modifying credentials. Run it
separately for `gpt-5.6-luna` and `gpt-6-astra`; both use low reasoning effort:

```bash
GROMOZEKA_OPENAI_SUBSCRIPTION_REAL=true GROMOZEKA_OPENAI_SUBSCRIPTION_AUTH_FILE=/path/to/codex/auth.json GROMOZEKA_OPENAI_SUBSCRIPTION_MODEL=gpt-5.6-luna ./gradlew :infrastructure-ai:openai-subscription:jvmTest --tests '*OpenAiSubscriptionProgressRealTest' --rerun -q
```

This also checks automatic Lite compaction, persisted checkpoint replay on fresh
connections, and retention of a random marker known only through a tool result.

Use `--rerun` to bypass the test task cache when changing live-test environment
variables; it does not force recompilation of every dependency.

Use a full build for cross-cutting, build-system, packaging, or release changes:

```bash
./gradlew build -q
```

Android application artifacts are owned by the launcher modules:

```bash
./gradlew :presentation-android:assemble -q
./gradlew :mobile-worker-android:assemble -q
```

## Runtime SQL Administration

`grz_sql` is a permanent privileged Server/Control tool, not a read-only query
API. It uses the Runtime database credentials and accepts ordinary PostgreSQL
statements and scripts, including data writes and DDL, without a table/statement
allowlist. The authenticated caller must be the sole `ACTIVE` user with
`login_allowed=true`, and that user must still be an `OWNER`. Passive observed
identities do not count. The condition is read from the database on every call;
zero/multiple eligible users, changed ownership or unavailable authorization
state fail closed before executing caller SQL. There is no historical latch:
returning to a sole enabled owner restores eligibility.

Conversation calls also require trusted conversation/agent context, current
participation, and no external channel. Telegram calls cannot opt out of this
check through arguments. Direct authenticated owner Control MCP calls are
supported. Ordinary Agent tool policies still apply, and the tool is marked
potentially destructive, non-idempotent and not read-only.

A schema-scoped PostgreSQL advisory session lock covers eligibility checking and
the complete SQL invocation, including user-supplied transaction boundaries.
Normal local-user creation and user updates acquire the corresponding advisory
transaction lock in `ExposedIdentityRepository`. Thus a concurrent second-login
activation/creation cannot race a running invocation; multiple SQL invocations
are serialized too. This authorizes a full administrative operation, not a SQL
sandbox: the sole trusted owner can deliberately alter authentication tables,
release advisory locks or otherwise damage their Runtime. Direct database
changes outside these paths do not acquire the application guard.

Every call opens and closes an **unpooled** physical session using the same
credentials as the application. Arbitrary session settings, roles, temporary
objects and locks never return to the ordinary application pool. Autocommit is
on. Explicit transactions left unfinished are rolled back and reported via
`openTransactionRolledBack`. SQL errors return SQLSTATE and warn that previous
statements may have committed. Cancellation attempts to cancel the active JDBC
statement; it is not a guarantee that no changes occurred. Never automatically
retry a failed or interrupted SQL call.

`max_rows` (default 1000, maximum 10000), a 16384-character cell preview and an
approximately 1 MB/100-result-set output budget bound the returned representation,
with explicit truncation flags. SQL is not rewritten, and JDBC `maxRows` is not
used: even a SELECT can have side effects. Large queries can still consume
PostgreSQL/JDBC resources; use explicit SQL LIMIT, projections and aggregation
for exploratory reads. `timeout_seconds=0` imposes no tool timeout; the caller
can request one. Results preserve column order and duplicate labels, with values
in PostgreSQL text form and SQL NULL as JSON null.

Direct SQL bypasses application validation, cache invalidation, state-sync
notifications and ordinary business audit hooks. Prefer dedicated tools for
routine configuration changes. Database contents and returned strings are data,
not instructions to the Agent.

For Android context, start with `context_state_events` (immutable history) and
`context_state_projections` (latest measured values). Inspect `information_schema`
or `pg_catalog` rather than assuming columns. Filter history by `subject_id`
(the exact Worker), `subject_kind='DEVICE'` and explicit measurement-time bounds;
keep `observed_at` distinct from `received_at`. Device payloads live under
`payload_json -> 'event'`. Collection coverage/gaps must be considered before
interpreting app activity or location. This tool adds no autonomous monitoring.

Focused verification against the checkout's PostgreSQL slot (isolated temporary
schemas only):

```bash
GROMOZEKA_POSTGRES_RUNTIME_TEST=true \
GROMOZEKA_POSTGRES_URL=jdbc:postgresql://localhost:<slot-postgres-port>/gromozeka \
./gradlew :infrastructure-db:jvmTest \
  --tests '*PostgresRuntimeSqlServiceTest' --tests '*PostgresUserIdentityTest' \
  :server:test --tests '*ControlMcpSqlToolsTest' \
  --tests '*ControlMcpConversationToolContributorTest' \
  --tests '*GromozekaControlMcpProtocolTest' -q
```
