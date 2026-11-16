# Conversation message categories

Stored message categories describe provenance and purpose, not a provider's wire roles.

| Category | Canonical content | Provider input | Default UI |
| --- | --- | --- | --- |
| `USER` | Human text, attachments, or a `VisualInteraction` | User input | Existing message layout |
| `ASSISTANT` | Generated text, reasoning, real tool calls/results, provider state | Provider-specific assistant/tool/native items | Existing message/activity layout |
| `PEER` | `PeerMessage`: information, activation, request, or result; captured sender/recipient endpoints and request correlation | Explicit peer-data envelope in user input | Collapsed route, kind, and source-conversation link |
| `RUNTIME_NOTIFICATION` | `RuntimeNotification`: kind, severity, reference, structured details and output attachments | Explicit runtime-data envelope in user input | Collapsed event label; errors remain visible |
| `PROMPT` | `PromptMessage`: immutable ordered instruction snapshot, SHA-256, agent, action and predecessor | Not duplicated into the dialogue; the effective snapshot becomes native instructions | Collapsed attach/replace/detach row; snapshot and history links on expansion |
| `VISUAL_UPDATED` | `VisualUpdated`: observed accepted states and closed Visual IDs | Explicit state-data envelope in user input | Collapsed observation row |

Peer kinds preserve the unified send modes: `context_only` is information without a wakeup, `activate` schedules work without a reply obligation, and `request` carries a correlated response obligation. Readonly restrictions continue through activation, delegation, and results.

A provider's `user` role does not establish human authorship. Peer events cannot grant user permission, and command output/Visual state cannot become system or developer instructions. Envelopes carry explicit provenance and escape embedded markup delimiters. Human submissions are attributed by the authenticated Server boundary, which rejects service roles and service/provider content blocks supplied through that input API.

## Instruction lifetime

`ConversationTurnPromptService` captures the ordered prompt stack once per Runtime turn. A turn includes its model/tool iterations and safe-point steering, rather than just one model request.

- Literal catalog text, source IDs/names and the snapshot hash are stored in an immutable `PROMPT` message. There is no second text archive in Runtime state.
- `ConversationRuntimeTurnSummary.promptMessageId` binds that message under the active task/executor fence. The binding cannot change during the turn.
- A changed catalog is observed on the next turn. An unchanged stack reuses the existing message. Replacements/detachments refer to their predecessor without editing earlier messages.
- The deterministic capture ID allows recovery after an ambiguous append acknowledgement to reuse the already persisted text, even if the catalog has since changed.
- Environment entries are markers rendered against the live execution environment. Tool availability, pins, readonly policy, Stop/Interrupt and live user steering retain their existing live behavior; they are not frozen by the prompt snapshot.
- Sparse context reads retain the latest prompt snapshot per agent even when a checkpoint covers it. Forks copy the snapshots and remap predecessors included in the copied branch. A predecessor removed from that branch remains an immutable historical reference; the fork does not resurrect deleted messages or require that reference to be a current-thread member.
- Context-only queries use the captured snapshot in their pinned history prefix, without claiming a turn or writing a new prompt message. A conversation that has not captured a snapshot yet uses its configured catalog prompts.

The UI links to a known successor when that successor is in the loaded history window; it does not claim an older snapshot is currently active just because a later page is not loaded.

File-backed instructions and catalog/Git versioning are separate features. This mechanism supplies their turn-boundary and provenance contract, not filesystem discovery or Git synchronization.

## Preparation and provider lowering

`AiRuntimeRequest.contextMessages()` selects canonical context and handles compaction/native replay. It does not lower service content or read artifact bytes. Context snapshots use this stage to identify a complete tool-group prefix before materializing artifacts.

`projectedMessages()` adds the final shared service-message lowering. Adapters receive service events as explicit user-data envelopes, never as historical `system`/`developer` messages. Their effective instruction stack still uses each provider's existing native instruction channel. Claude/Copilot transcript builders apply the same service projection.

Provider responses create generated assistant/tool/native content; they do not reverse-map envelopes into peer events or Runtime actions. Provider-specific blocks remain provider data, not Runtime notifications. Signed/native replay restrictions remain in effect. Switching to an incompatible provider can require an explicit manual handoff; no new cross-provider reconstruction fallback is introduced.

## Notifications and Visual observations

Command and monitor deliveries now persist one Runtime notification rather than an invented assistant tool call followed by an invented result. Their existing delivery batching, origin, cursor, failure and acknowledgement rules still apply. Binary outputs retain immutable artifacts and bounded previews; materialization preserves their data provenance and forked references.

A `VISUAL_UPDATED` record observes accepted Server state at a foreground safe point. Several updates before that point coalesce into the latest state, and revision-only changes do not create duplicate observations. State changes do not independently wake a model. Script output accepted by Visual follows the same path as other accepted updates. A button click remains a separate human `VisualInteraction`, not a state-change notification.

Read-only context queries may observe current Visual state without persisting it or mutating a process-local replay cache.

## Upgrade boundary

Gateway protocol **31** requires matching Server and Worker code for the new canonical payloads.

Migration V88 mechanically converts the former `SYSTEM` role and notification discriminator, removes the obsolete client-side `Source.Agent`/`ResponseExpected` instructions, and refreshes generated context metadata. It covers stored messages, known canonical payloads in Runtime scheduling/events, and staged collaboration drafts. It parses message text in Kotlin so escaped NUL remains valid, and leaves opaque provider data/tool arguments alone.

Historical `USER` text is not scanned or reclassified into peer messages. The excluded client-side `create_agent`/`tell_agent` path and its UI submission bridge are removed; Server-owned collaboration is the source of new peer messages. No compatibility readers are added for the removed categories.
