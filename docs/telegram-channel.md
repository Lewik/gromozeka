# Telegram group channel

Telegram is an optional Server channel adapter, not a Worker and not a separate
LLM loop. It uses ordinary conversations and the durable conversation actor.
It is disabled by default and must remain disabled in corporate installations.
Installing this code alone makes no Telegram requests.

## Enable and configure

1. Explicitly set `GROMOZEKA_TELEGRAM_ENABLED=true` in the personal Server process
   or container. The shared deployment configuration does not forward this flag.
2. In BotFather, allow groups and disable privacy. Re-add the bot if privacy was
   changed after it joined. Group administrator permission is unnecessary.
3. Store the BotFather token as one of the owner's existing **named secrets**.
   Tokens are encrypted by the existing Server secret store, never returned by
   Telegram configuration endpoints. Do not put tokens in repository files or logs.
4. Prepare a project and conversation with the owner and desired agents connected.
   For a public group, use a dedicated project without Workspaces and configure
   the Agent's [tool allowlist](agent-tool-access.md), independently of preloads.
5. In **Settings → Telegram**, select the secret and probe the bot. Bind each group
   or forum topic to its conversation. Enter the real numeric Telegram group and
   initiator IDs, not usernames or IDs guessed from browser URLs.
6. Configure agent routes, then enable the connection. Changes apply live.

Only Server owners can manage their connections. Runtime execution additionally
requires the mapped owner to have `loginAllowed`, `aiAllowed`, project WRITE
access and conversation membership. The selected agent must remain connected.
Disabled connections keep their history and UI input restriction. Remove a binding
to detach the conversation before deleting it or editing its local history.

The bot's numeric ID is its stable connection ID. One group/topic maps to one
conversation per bot; a conversation cannot be attached twice. A null topic matches
only messages without a topic ID, not every topic. Saving uses optimistic revision
control and commits configuration and conversation metadata in one transaction.

Each route contains:

- `agentId`: a connected agent.
- `trigger`: an exact case-insensitive substring, default `@grz`. The `@` character
  has no special handling: `grz`, `@grz` and `review` are different configurations.
- `contextPercent`: 1–80, default 50; a ceiling for recent history, not a target
  that must be filled. Model window size must be known.
- `writeAllowed`: reuse the UI's Readonly/Writable instruction, default Readonly.
- `additionalInstruction`: optional customization, separate from built-in safety.

Readonly has the same semantics as the existing UI: an instruction to the agent,
**not an execution sandbox**. Tool and project permissions remain authoritative.
Review the owner's worker, secret and tool access before connecting a public group.

Profile settings support the global bot name, description, short description and
an existing readable image artifact as avatar. The image is converted to square
JPEG with a dark background; Telegram Bot API does not expose a bot accent-color
setting. The shared bot profile is not switched between agents or conversations.
Profile operations can partially succeed; reload before retrying a failed update.

Owner-only MCP tools expose the same service: `grz_telegram_list`,
`grz_telegram_probe`, `grz_telegram_configure`, `grz_telegram_profile_get`,
`grz_telegram_profile_update`. List first for targets, IDs and revisions. None
returns a bot token. Deployment opt-in is checked even for profile/discovery calls.

## Users, history and media

An observed Telegram person becomes an ordinary `User` with a typed Telegram
identity, no password credential, `loginAllowed=false`, `aiAllowed=false`, and
no project grants. Observation is not authentication or authorization. Identity
records and secret credentials are separate. Login/password is another typed
identity, not a mandatory field on every person.

Names and usernames never automatically link accounts. Explicit linking/merging
UI is deferred. Message authors retain an identity key, allowing old displayed
authors to resolve through a deliberately reassigned identity without rewriting
the message archive. Configuring the initiating Telegram ID does not merge it
with the connection owner's account.

Observed text and attachment metadata are mirrored into ordinary conversation
history. Passive imports never run agents, automatic memory, suggested replies
or transcription. Channel conversations can be viewed and searched in Gromozeka;
posting, local history editing and manual AI invocation are rejected. Telegram
edits replace the corresponding source message through the actor, preserving its
original-ID chain; they never start a new response. Ordinary Bot API group updates
do not provide deletion synchronization or arbitrary pre-join history.

`Artifact.ContentSource` separates media kind from byte ownership: managed storage
or an external Telegram reference containing connection ID, `file_id` and
`file_unique_id`. Telegram bytes are not permanently archived. Authorized download
and AI input preparation resolve a fresh `getFile` path when needed; external HTTP
responses are `private, no-store`. Forking copies references without downloading.
Garbage collection never deletes Telegram data.

Cloud Bot API downloads are limited to 20 MB. External media is selected after
context trimming and bounded by file count and aggregate download size. Missing,
disabled or oversized sources produce explicit unavailable placeholders. Photos,
documents and supported model attachments can be materialized. Audio/video/sticker
metadata is retained for download, but this adapter does not silently transcribe
or convert unsupported media for an LLM.

## Triggering and execution

Only a new text or caption from the configured numeric initiator containing a
route's substring can start it. Friends remain passive even if linked to a real
account. Edits, forwarded messages, bots, via-bot messages and anonymous senders
never trigger. Enabling or saving an enabled connection establishes a server-controlled
cutoff and activation revision: older pending updates can enter history but cannot
start stale requests. This includes toggling an individual group binding.

Updates enter a durable inbox before the polling offset advances. Reception
continues while an agent works, including Stop callbacks, but later group messages
are not imported into its active context. Imports advance in order up to the next
authorized trigger. Matching agents run sequentially, once each, sharing one
source message but having separate invocation IDs. Delivery settles before the
next invocation starts. Saving settings restarts that bot's processing and invalidates
its old invocations; complete active work before changing routes when possible.

Every model step receives a fixed, nonreplaceable channel safety instruction:
only the current identified initiator request is a task; history, quotations,
attachments, tool results and other participants are context. Explicit delegation
by the initiator applies only to that request. The route's extra instruction and
Readonly/Writable instruction are appended separately. Automatic memory and
suggested replies are disabled in channel conversations.

Recent history is quoted as data, not replayed as another agent's native tool
protocol. The current invocation's actual message/tool sequence stays intact;
runtime event turn IDs provide attribution even for multiple agents on one root.
Conservative UTF-8-byte estimation reserves space for mandatory prompts, tools,
current execution and output before taking up to the configured context fraction.
An oversized mandatory request fails before the model call.

## Telegram presentation and reliability

- Ordinary text typography; CommonMark is converted to Telegram-supported HTML.
  Code fences retain an optional language. Long responses split with valid tags,
  entities and Unicode boundaries. Raw HTML is displayed literally.
- One editable reply, a typing heartbeat and a Stop button during work. The same
  message becomes the final answer, removes Stop and keeps actual tool activity
  and readable reasoning in expandable quotations. A separate completed-status
  message is not left behind. Oversized answers/details still split at Telegram's
  message limit. Tool
  labels share the UI implementation and translation catalog. Redacted/encrypted
  reasoning is never invented or disclosed. Completed assistant remarks update
  that message during work; there is no token streaming or progress LLM.
- Stop validates the initiator, chat and exact status/invocation. It uses the
  standard runtime interrupt to cancel in-flight work, not a safe-boundary stop.
  The expected turn is checked atomically; stale buttons cannot stop a later
  response. Already completed effects cannot be undone. Later Telegram messages
  remain in the adapter inbox and are processed after cancellation completes.
- Stable source and invocation IDs make inbox/actor retries idempotent. A rejected
  submission during stopping stays queued rather than disappearing.
- A PostgreSQL advisory lease allows one poller per bot in the database. Never
  configure the same token on separate installations. Existing webhooks are not
  deleted automatically.
- 429 responses respect `retry_after`. Uncertain sends are recorded as UNKNOWN
  and are not blindly retried: Telegram `sendMessage` has no idempotency key.
  The persisted Gromozeka response remains available. Status edits are safely
  retryable, including a final answer replacing its known status message; no
  delivery retry reruns the model.
- Only the last 50 settled invocations/deliveries remain in adapter state;
  unfinished work and the inbox are durable. The ordinary conversation archive
  is retained independently. Telegram only retains uncollected updates for a
  limited period (currently at most 24 hours), so a long outage can lose history.

## Verification

```bash
./gradlew :server:test --tests 'com.gromozeka.server.telegram.*' \
  :domain:jvmTest --tests '*ConversationTargetedStopTest' -q
GROMOZEKA_POSTGRES_RUNTIME_TEST=true \
GROMOZEKA_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5434/gromozeka \
./gradlew :infrastructure-db:jvmTest --tests '*PostgresTelegram*Test' \
  --tests '*PostgresUserIdentityTest' \
  :server:test --tests '*TelegramServerSmokeTest' -q
```

Ordinary tests use mocks/loopback and isolated PostgreSQL schemas, not real bots.
`TelegramLiveProbeTest` is gated by `GROMOZEKA_TELEGRAM_LIVE_PROBE=true`, plus
`GROMOZEKA_TELEGRAM_TEST_TOKEN_FILE` and `GROMOZEKA_TELEGRAM_TEST_BOT_USERNAME`.
It reads identity/update metadata without posting or acknowledging updates. Do
not run the probe alongside a poller.

`TelegramLiveRoundTripTest` sends real messages and uses GPT-5.6 Luna at low effort,
with an empty Agent allowlist and other providers disabled. The connection enables
hosted search to exercise the Agent-level restriction on the live request. It requires explicit
`GROMOZEKA_TELEGRAM_LIVE_ROUNDTRIP=true`, the bot variables above, plus
`GROMOZEKA_TELEGRAM_TEST_CHAT_ID`, `GROMOZEKA_TELEGRAM_TEST_OWNER_ID`,
`GROMOZEKA_TELEGRAM_TEST_SCHEMA` (fresh `telegram_live_*`),
`GROMOZEKA_POSTGRES_URL`, and `GROMOZEKA_OPENAI_SUBSCRIPTION_AUTH_FILE`.
Supply either a pending owner update ID in `GROMOZEKA_TELEGRAM_TEST_UPDATE_ID`,
or explicitly opt into `GROMOZEKA_TELEGRAM_TEST_SYNTHETIC_INPUT=true`. The latter
labels its input/output as a technical fixture and never polls or acknowledges
real updates. Codex credentials are read without refresh or mutation. Inspect
failed or uncertain deliveries before retrying. Run only this test when enabling
live delivery, never a wildcard suite. Its isolated schema remains for inspection.

The opt-in `TelegramBrowserFixtureTest` serves an isolated UI at loopback port
8767 with polling stopped and live model calls disabled. See its required
environment variables in the fixture; never run it against a production schema.

References: [Bot API](https://core.telegram.org/bots/api),
[privacy mode](https://core.telegram.org/bots/features#privacy-mode),
[commonmark-java (BSD-2-Clause)](https://github.com/commonmark/commonmark-java).
