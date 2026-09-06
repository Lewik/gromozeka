# Claude Code latency investigation — 2026-09-06

## Current conclusion

The Russian multi-turn probe reproduced the reported scale of slowdown:
structured answers took 71–131 seconds where the plain-text controls took
14–25 seconds. Incoming JSON deltas showed model-written Unicode escapes and
roughly seven times as many output tokens, with no duplicate main request or
long streaming pause. Inline suggestions were included in a separate control;
their generation did not require an auxiliary model call.

A test-only literal-Unicode system instruction is **not a reliable fix**.
Repeating it on every turn also failed, both inside the user text and as a
literal `<system-reminder>` appended after the serialized transcript. Escape
generation returned on turn four in both reminder trials. A further trial of
the report's exact tool-parameter UTF-8 wording, adapted only to Russian, also
returned to escapes on turns four and five. This rules out the tested
formulations as reliable mitigations, not every possible reminder.

The best tested candidate is **the same JSON contract directed by the prompt,
without CLI `--json-schema`**. Across three synthetic sessions, 25 long Russian
answers and 10 external-action requests passed strict fixture validation with
zero generated Unicode escapes. The final two sessions used no Unicode
instructions: ten consecutive answers took 12–23 seconds each; ten tool-plus-
answer cycles took 16–28 seconds each. The production implementation now uses
this mode, with local schema validation and at most three correction attempts
in the same process. See [Production implementation](#production-implementation)
for the current code and verification. The experiments below are chronological:
statements about test-only behavior describe the pre-implementation stage.

The actual Ubuntu work incident still needs a metadata-only trace to confirm
its cause.

## Scope and method

The initial investigation used `main` at `240ba1910` in `dev0`. That stage
added only diagnostic instrumentation, synthetic probes and their tests.
The subsequently approved implementation is described at the end of this report.

Environment: macOS, Claude Code **2.1.260**, `claude-opus-5`, adaptive thinking,
**medium** effort, standard speed, local authorized Claude subscription.
No corporate prompts, credentials or work data were used. No running dev Server
was found at preflight; none was started or stopped.

Compared the same synthetic 120–150-word question in four configurations:
plain CLI input through the existing process transport (without the Gromozeka
transcript adapter), the actual `ClaudeCodeCliRuntime` in text mode, JSON schema
mode, and Gromozeka's external-action schema with one advertised test tool.
Each configuration used one cold and two warm calls with the same model/effort,
stable system prompt and persistent process. These are provider/component
measurements, not interactive-terminal or complete browser/Server end-to-end
measurements. Cold results include startup/JVM warmup; this is not a statistical
benchmark of model inference speed.

The opt-in capture wrapper forwards stdin/stdout unchanged, records each line
with a monotonic timestamp, enables partial messages, and captures the CLI's
own `--debug-file`. There is no TLS interception. Both compared paths use the
same capture overhead. Application diagnostics record metadata, not prompt,
answer, attachment, thinking or credential contents.

## Measurements

Elapsed seconds at the Kotlin caller, including parsing:

| Configuration | Cold | Warm 1 | Warm 2 |
| --- | ---: | ---: | ---: |
| Plain CLI text | 6.894 | 4.142 | 4.867 |
| Gromozeka text | 5.946 | 4.452 | 4.748 |
| Gromozeka JSON schema | 8.421 | 4.318 | 4.531 |
| Gromozeka external-action wrapper | 9.732 | 4.418 | 5.119 |

For the text cases, the first text delta arrived **0.96–2.18 seconds** after
sending input. Gromozeka waits until the complete result, around 4–6 seconds.
Warm request preparation and stdin flushing were approximately 0–3 ms.
The plain-text adapter did not exhibit a large sustained overhead in these
small samples. No duplicate stdin submissions or HTTP retry events occurred
in the baseline.

### A real extra generation inside structured output

The first external-action-wrapper call emitted:

```text
~6.22 s  StructuredOutput({response: {response: {...}}})
~6.23 s  tool_result error: output does not match required schema
~9.68 s  StructuredOutput({response: {kind: final_answer, ...}})
~9.68 s  result success
```

There was **one stdin request but two distinct Opus API message IDs**. The first
answer had an extra `response` nesting level; Claude Code returned validation
feedback to its model and generated another answer. Roughly 3.45 seconds were
spent after the failed submission. The next two calls in that process did not
repeat this error. This demonstrates a possible overhead, not an assertion
that every structured-output call doubles execution.

Claude's [structured-output documentation](https://code.claude.com/docs/en/agent-sdk/structured-outputs)
explicitly describes re-prompting after schema validation fails. Do not confuse
`num_turns=2` with two model requests: a successful `StructuredOutput` tool
invocation had that count with only **one** API message in other samples.
Likewise, `assistant` plus `result` can represent the same answer, not duplicate
generation. `duration_api_ms` and `modelUsage` accumulate across warm CLI turns;
use the per-input monotonic interval rather than treating them as per-call time.

### The complete Worker path does not stream model output

`ConversationEngineService.runLlmCallStep` calls `runtime.call`. Worker-targeted
runtime streaming is explicitly unsupported in `TargetedAiRuntimeProvider`.
`ClaudeCodeCliRuntime.stream` also wraps a completed `call`. The process reader
consumes assistant events but returns only on `result`.

Thus a fast first token does not become visible in Gromozeka. Adding
`--include-partial-messages` for diagnostics does not implement UI streaming.
Claude's [streaming documentation](https://code.claude.com/docs/en/agent-sdk/streaming-output)
also distinguishes partial events from the final structured result.

### Auxiliary calls can replace the conversation session

Reproduced using the same conversation/thread/project/model configuration for
the main answer and a `SUGGESTED_REPLIES` call with its own prompt/history:

```text
main 1       STATE_NOT_FOUND             sentMessages=1
auxiliary 1  RESET_MESSAGE_IDS_CHANGED   process replaced
main 2       RESET_MESSAGE_IDS_CHANGED   sentMessages=3
auxiliary 2  RESET_MESSAGE_IDS_CHANGED   process replaced
main 3       RESET_MESSAGE_IDS_CHANGED   sentMessages=5
```

`sessionStateKey` includes conversation, thread, project, workspace, connection,
model configuration and model, but **not the call's purpose**. The auxiliary
request replaces session metadata and the cached process. Main calls then
resend full history instead of just the new message. This does not prove that
Anthropic's prefix cache always misses across those fresh processes.

`ConversationEngineService` also awaits separate suggested replies **before**
persisting/emitting the already completed answer. In the synthetic fixture,
the tiny auxiliary calls took 1.97 and 2.18 seconds, plus startup/wrapper cost;
main-plus-auxiliary took 8.12 and 7.09 seconds.

These findings are conditional: the production call uses this separate mode
only if configured; the repository default is **INLINE**. Session collision
also requires the same connection/model configuration. No claim is made that
the user's work setup currently has those settings.

### Bounded delivery polling, not a multi-second artificial throttle

`WorkerRequestService.deliver` polls at 250 ms; `await` polls at 100 ms. A
component test with an in-memory repository, one session and 20 ms simulated
execution measured **104, 220, 314, 221, 217 ms** total, five distinct executions
for five requests. This excludes real PostgreSQL/network latency.

No unconditional multi-second sleep was found in the Claude call path. Process
cache pruning runs separately. One-shot process shutdown can wait 1+1 seconds;
persistent successful calls do not take that close path. Memory-stage retry
backoff exists (750 ms up to 4 seconds) but only follows retryable errors, not
every normal conversation request.

### Other observed differences and limits

- Cold CLI processes made an auxiliary Haiku request with
  `source=generate_session_title`, alongside the Opus request. This occurred
  in both the plain and wrapped paths; it is not Gromozeka posting twice.
- CLI reported `fast_mode_state=off`, reason `sdk_opt_in_required`. Noninteractive
  fast mode requires explicit settings according to the
  [official fast-mode documentation](https://code.claude.com/docs/en/fast-mode).
  It was **not enabled**: it can incur separate usage-credit charges. Whether
  the user's interactive work CLI uses fast mode is unknown.
- One external-action round trip completed in 4.06 seconds, including two
  legitimate model calls. The next user request was rejected by Claude API
  with `reasoning_extraction`. A separate 132,790-character history experiment
  was rejected with the same category on its first call. These failures were
  preserved, not retried with bypasses, excluded from latency conclusions,
  and **do not establish large-history performance**.
- The actual work model, effort, network, tool catalog, suggested-reply mode,
  memory configuration and interactive output style remain unverified. The
  provider disables user/project customizations via its existing CLI flags.

## Diagnostics and reproduction

The same `call` ID now links `AI_TURN_TRACE`, `AI_GATEWAY_TRACE` and
`CLAUDE_CODE_TRACE`. `WORKER_REQUEST_TRACE` links that call to its durable
request ID, dispatch queue time, execution and result acknowledgement.
The timeline includes prompt/schema sizes and fingerprints, session reset
reason, cache wait, process PID, stdin flush, every stdout event, event gaps,
model message IDs, tool-error count, structured-output nesting shape, token
usage, CLI retry/speed metadata, completion and message emission.

Enable DEBUG for `com.gromozeka.infrastructure.ai.claude` on the Worker, and
the relevant Server/application loggers for end-to-end collection. Set
`GROMOZEKA_CLAUDE_CODE_TRACE_PARTIALS=true` on the Worker to include token-level
event metadata. These flags do not change effort, models, tools or response
delivery semantics. Existing runtime log rotation still applies.

POSIX synthetic probe (the output directory must be a fresh private directory):

```bash
probe_dir=$(mktemp -d /tmp/gromozeka-claude-probe.XXXXXX)
GROMOZEKA_CLAUDE_SYNTHETIC_PROBE=true \
GROMOZEKA_CLAUDE_DIAGNOSTIC_DIR="$probe_dir" \
GROMOZEKA_CLAUDE_CODE_REAL_EXECUTABLE="$(command -v claude)" \
GROMOZEKA_CLAUDE_CODE_EXECUTABLE="$PWD/scripts/trace-claude-code.mjs" \
GROMOZEKA_CLAUDE_CODE_MODEL=claude-opus-5 \
./gradlew :infrastructure-ai:jvmTest --rerun --tests '*ClaudeCodeLatencyProbeTest' -q
node scripts/summarize-claude-code-trace.mjs "$probe_dir"
```

Default: the four baseline cases, three turns each. Optional settings:
`GROMOZEKA_CLAUDE_PROBE_CASES` (comma-separated case names),
`GROMOZEKA_CLAUDE_PROBE_TURNS` (1–10), `GROMOZEKA_CLAUDE_PROBE_EFFORT`, and
`GROMOZEKA_CLAUDE_PROBE_REFERENCE_LINES` (0–1000).
Additional cases: `runtime-shared-auxiliary`, `runtime-tool-loop`.

Raw capture is explicitly gated to synthetic probes. It records full prompts
and model output in private files, never credentials/environment dumps. Do not
use or share such captures for corporate conversations. Normal runtime DEBUG
logs do not contain those payloads.

Local evidence from this run:

- `logs/claude-latency-baseline-result.xml` and `logs/claude-latency-baseline-summary.jsonl`
- `logs/claude-latency-auxiliary-result.xml`
- `logs/claude-latency-tool-loop-result.xml` and `logs/claude-latency-history-result.xml` (API refusals)
- `logs/claude-latency-verification.log`
- Raw baseline `/tmp/gromozeka-claude-latency.KDRIXz`
- Raw follow-up `/tmp/gromozeka-claude-followup.lyle6i`
- Raw large-history refusal `/tmp/gromozeka-claude-history.snqfq5`

Focused compilation and tests passed for the Claude adapter/cache/diagnostics,
targeted AI routing, conversation dispatcher, Worker Gateway and durable
request service. Real probes are explicitly opt-in; a normal test run does
not repeat them or turn the recorded API refusals into successful real tests.

## Proposed fixes, not implemented

1. Confirm the Unicode-escape fingerprint on the work Worker. The tested
   system-only and per-turn instructions failed; do not treat them as a fix.
   Evaluate a provider-specific response path that does not require long
   natural-language answers inside tool JSON, retaining explicit tool routing.
2. Simplify/clarify the external-action structured-output envelope and measure
   validation retries across independent cold sessions before selecting a fix.
3. Add transient progress/stream events across the Worker boundary, retaining
   the existing durable final-result semantics. Do not pretend that merely
   enabling CLI partial messages accomplishes this.
4. Separately isolate auxiliary semantic sessions and avoid holding back the
   visible answer for separate suggested replies. This is not the user's
   inline-suggestions setup and does not explain this work incident.

Confirm the work configuration before attributing its slowdown to any single
finding. Capture a correlated timeline there using approved local tooling;
do not send corporate content through unapproved providers.

## Follow-up: Russian multi-turn dialogue and minute-scale latency

The user clarified that the work Worker runs on Ubuntu Linux and suggested
replies are generated inline. The separate-suggestions finding above therefore
does **not** explain the reported work slowdown. The original short English
probe also did not exercise Cyrillic output in structured tool arguments.

The follow-up uses five related questions about coastal climate, sea breezes,
mountains and rainfall. Answers request 10, 15, 20, 12 and 18 numbered Russian
sentences. Every warm turn continues the same process/session and retains the
earlier answers. All advertised external actions remain unused; `StructuredOutput`
is the CLI's own output mechanism. The `runtime-inline` fixture additionally
includes `fullText`, `ttsText`, `voiceTone`, `attentionRequested` and
`suggestedReplies` in one schema/call. It mirrors the application contract's
field structure without importing application internals into infrastructure.

Baseline caller elapsed seconds (all 20 calls completed successfully):

| Case | 10 sentences | 15 sentences | 20 sentences | 12 sentences | 18 sentences |
| --- | ---: | ---: | ---: | ---: | ---: |
| Plain CLI text | 13.09 | 15.45 | 24.38 | 13.88 | 20.94 |
| Gromozeka text, no tools | 13.82 | 14.60 | 24.68 | 14.70 | 22.15 |
| External-action wrapper, text answer | 14.42 | 80.29 | 122.97 | 71.50 | 94.03 |
| External-action wrapper, inline suggestions | 16.60 | 101.37 | 130.71 | 83.22 | 109.19 |

The first answer in each structured session used literal Cyrillic; all four
subsequent answers used escapes. Across the baseline there were 20 stdin
submissions, 20 main-model API messages, no validation failures and no retry
events. The longest stdout-event gap was 2.76 seconds. All answers contained
the requested number of numbered sentences; the inline fixture returned two
suggestions per answer. Warm runtime calls all reported `RESUME_WITH_DELTA`
with one new message; there was no session reset or full-history resend.

### Observed Unicode escape generation

On the second external-action-wrapper turn, the raw `input_json_delta`
fragments switched from literal Cyrillic to JSON Unicode escapes, for example
`\u041c\u043e` rather than `Мо`. This occurred in the incoming CLI stream,
before Gromozeka parsed or displayed the result. The answer still decoded into
ordinary Russian, so inspecting the final answer alone hid the inflation.

The 15-sentence turn took **80.28 s / 5,309 output tokens / 1,256 escapes**.
The 20-sentence turn took **122.96 s / 7,575 tokens / 1,804 escapes**.
The following 12-sentence turn took **71.50 s / 4,621 tokens / 1,086 escapes**.
The plain-CLI versions of the same questions took 15.45 s / 753 tokens,
24.38 s / 1,087 tokens, and 13.88 s / 667 tokens respectively. Answers are
independent generations, not byte-identical outputs.

Each slow turn had one stdin submission, one main model API message and no
validation error. No long event gap occurred: output kept arriving while the
model generated the expanded representation. This is a reproduced
minute-scale mechanism, not merely a missing-streaming perception. It does not
yet prove that the same mechanism caused the user's Ubuntu incident.

Selecting the application's `TEXT` answer format alone does not remove this
path: when external tools are available, the Claude adapter still wraps the
answer in its structured external-action protocol. The slow `runtime-tools`
case uses precisely that combination. Removing all tools would change the
agent's capabilities, so it is a comparison control, not a recommended fix.

`ClaudeCodeJsonDeltaDiagnostics` now counts incoming JSON characters and Unicode
escapes across delta boundaries, without recording their content. Tests cover
split escapes, literal backslashes and exclusion of ordinary text deltas.
The trace summarizer reports per-model-call token usage, longest stdout-event
gap, time after the last delta, raw JSON length and escape count.

### Related public reports and bounded workarounds

- [#79339](https://github.com/anthropics/claude-code/issues/79339) describes
  model-written Unicode escapes in Korean tool arguments, malformed escapes
  and the resulting token inflation. It explicitly notes that spelling each
  character in hexadecimal consumes several times more tokens. Its main
  symptom is invalid tool JSON, not measured Russian response latency.
- [#83033](https://github.com/anthropics/claude-code/issues/83033) describes the
  same escape-writing behavior for Korean tool arguments, including a later
  Opus 5 report. The original investigation focuses on character corruption,
  not latency. A [maintainer response](https://github.com/anthropics/claude-code/issues/83033#issuecomment-5306359017)
  points to model output behavior and recommends a literal-Unicode instruction
  as mitigation. This is much closer to the locally observed fingerprint than
  generic slowness reports.
- [#91413](https://github.com/anthropics/claude-code/issues/91413), opened
  September 2, reports individual Opus 5 sessions slowing 10–30x while
  concurrent sessions stay fast. Its author reports that a fresh session helps
  while resume/downgrade does not. The trigger is not established; it is not
  evidence that this is our cause.
- [#85791](https://github.com/anthropics/claude-code/issues/85791) instead reports
  stalls up to 15 minutes relieved by a full process restart, but not merely a
  new thread. These are different empirical workarounds, not a universal fix.
- [#87930](https://github.com/anthropics/claude-code/issues/87930) reports a
  Bedrock-gateway streaming failure followed by a silent non-streaming retry.
  The [official environment reference](https://code.claude.com/docs/en/env-vars)
  documents `CLAUDE_CODE_DISABLE_NONSTREAMING_FALLBACK=1`. This disables that
  fallback, not all retries, and can expose an error rather than make a request
  succeed. It is a diagnostic option only if traces show fallback; the local
  slow Russian turns did not.
- The [official network guide](https://code.claude.com/docs/en/network-config#streaming-idle-watchdogs)
  documents separate first-byte, byte-idle and event-idle watchdogs. Do not
  blindly lower `CLAUDE_STREAM_IDLE_TIMEOUT_MS`: values below five minutes are
  clamped. The Gromozeka Worker must inherit the same approved proxy/CA/provider
  configuration as the interactive CLI; its existing `--setting-sources ""`
  deliberately excludes user/project configuration files. No TLS verification,
  corporate policy, gateway or provider restrictions were changed in this run.

The literal-Unicode mitigation is an opt-in **probe-only** system instruction
(`GROMOZEKA_CLAUDE_PROBE_LITERAL_UNICODE=true`), not a production prompt change.
Enable the dialogue with `GROMOZEKA_CLAUDE_PROBE_PROFILE=dialogue` and select
`runtime-inline` to include inline suggestions. The probe's per-turn deadline
is five minutes, so minute-scale responses are measured rather than prematurely
discarded.

The tested instruction was:

```text
In JSON string values, write Cyrillic and other non-ASCII text as literal Unicode
characters. Do not encode ordinary letters as backslash-u hexadecimal escapes.
Use only the escaping JSON syntax requires.
```

In the external-action-wrapper dialogue this produced zero Unicode escapes
on the first three turns, but **1,296 and 1,527 escapes** on turns four and
five, taking **86.75 and 106.47 seconds**. The first turn additionally retried
an invalid nested schema once; those 26.52 seconds must not be interpreted as
pure inference time. The two clean warm turns took 18.09 and 25.39 seconds.
This disproves reliable suppression by that system instruction in this setup;
it does not establish that every possible Unicode reminder will fail.

The inline-suggestions arm also failed to suppress escapes: its five calls
took 14.29, 15.62, **119.73, 73.93 and 99.60 seconds** at the caller.
The last three contained **1,739, 1,023 and 1,402 escapes** respectively,
with one model request each and no schema errors. All ten calls in the
system-only trial completed; the six diagnostics tests and seventeen process
cache tests passed in the same Gradle invocation.

For any further prompt experiment, use the application's prompt configuration,
not just `CLAUDE.md`: Gromozeka's Claude launch intentionally disables that
customization path. No production prompt was changed.

After the diagnostic changes are installed, set these variables in the **Worker's**
launch environment to observe the fingerprint without recording message content:

```bash
GROMOZEKA_CLAUDE_CODE_TRACE_PARTIALS=true
LOGGING_LEVEL_COM_GROMOZEKA_INFRASTRUCTURE_AI_CLAUDE=DEBUG
```

Search the rotated Worker log for `jsonUnicodeEscapes=` on the slow call.
The counter needs partial events: zero with `jsonDeltaChars=0` does not establish
that there were no escapes. Compare the same benign dialogue in a fresh
conversation before and after the prompt instruction. Preserve approved
provider/network settings, and do not upload corporate conversation captures.

Follow-up evidence:

- `logs/claude-latency-dialogue-result.xml`
- `logs/claude-latency-dialogue-summary.jsonl`
- `logs/claude-latency-unicode-result.xml`
- `logs/claude-latency-unicode-summary.jsonl`
- Raw dialogue `/tmp/gromozeka-claude-dialogue.EvWHkS`
- Raw Unicode-instruction trial `/tmp/gromozeka-claude-unicode.Ql2RXK`

### Per-turn reminder experiment

The user suggested repeating the Unicode instruction in each new message,
analogous to Claude Code output-style reminders. The installed 2.1.260
distribution has `Concise.turnReminder`; its output-style attachment handler
emits a per-turn metadata message. This is a harness-added message, not a
distinct API role or an automatic guarantee of instruction compliance.
The [official output-style guide](https://code.claude.com/docs/en/output-styles)
documents reminders during the conversation, and the
[Claude Code prompt-caching article](https://claude.com/blog/lessons-from-building-claude-code-prompt-caching-is-everything)
describes placing `<system-reminder>` blocks in new user messages or tool
results rather than changing the cached system prompt.

`GROMOZEKA_CLAUDE_PROBE_UNICODE_REMINDER=true` repeats the exact same Unicode
instruction on each call. The existing system instruction, inline schema, tool
list, model and effort remain unchanged. The reminder is probe-only; no normal
provider behavior or configured prompt was changed.

Two placements must be distinguished:

- Default `GROMOZEKA_CLAUDE_PROBE_REMINDER_PLACEMENT=message`: append the reminder
  to each synthetic user question. The normal transcript serializer escapes
  its tags to `&lt;system-reminder&gt;`, so this tests repeated user-text guidance,
  not a separate harness metadata block. The first three turns had zero
  escapes and took 17.23, 19.29 and 27.93 seconds. Turn four took **91.74 s**,
  generated **1,329 escapes**, and had one API request with no schema retry.
  The ten-turn trial was deliberately stopped during turn five after that
  counterexample. Its JUnit failure records our termination, not an additional
  provider defect; only the four completed turns are measurements.
- `GROMOZEKA_CLAUDE_PROBE_REMINDER_PLACEMENT=runtime`: a test-only executor
  decorator appends the literal reminder block after the complete serialized
  transcript and external-action reminder, just before passing the command to
  the real process executor. This also applies to follow-up calls after a tool
  result. It leaves the static system prompt and recorded conversation history
  unchanged. The planned ten-turn inline trial repeats the five climate
  questions twice in one conversation, retaining all preceding answers.

The literal runtime-reminder trial also failed on turn four:

| Turn | Requested sentences | CLI round-trip seconds | Output tokens | Unicode escapes |
| --- | ---: | ---: | ---: | ---: |
| 1 | 10 | 13.68 | 669 | 0 |
| 2 | 15 | 16.22 | 864 | 0 |
| 3 | 20 | 22.43 | 1,145 | 0 |
| 4 | 12 | 81.30 | 5,121 | 1,195 |

Every completed turn had one main model request and no schema error. The raw
stdin confirms literal reminder tags, not XML-escaped tags, on each call.
After this counterexample, the trial was deliberately terminated during turn
five; the resulting JUnit failure is not a provider failure measurement.
The remaining planned turns were not run. The repeated instruction may affect
probabilities, but neither this sample nor the system-only experiment establishes
reliable escape suppression. No production mitigation was installed.

The normal adapter already appends `ClaudeCodeToolProtocol.runtimeReminder()`
in `buildUserInput`, including calls carrying tool results. If a reminder proves
reliable, that provider boundary is a possible integration point; putting it in
the Server's stored user message or changing every provider is unnecessary.

The user-text reminder evidence is in `logs/claude-latency-reminder-result.xml`,
`logs/claude-latency-reminder-summary.jsonl`, and
`/tmp/gromozeka-claude-reminder.pOCqKI`.
The runtime-reminder evidence is in
`logs/claude-latency-runtime-reminder-result.xml`,
`logs/claude-latency-runtime-reminder-summary.jsonl`, and
`/tmp/gromozeka-claude-runtime-reminder.lpvY7G`.

After the deliberately stopped real probes, the final non-live verification
passed: 18 Claude runtime tests, 17 process-cache tests and 6 diagnostics tests.
The log is `logs/claude-latency-final-verification.log`. Both terminated probe
process trees were checked to be gone; no Server or other Worker was stopped.

### Tool-parameter UTF-8 wording

The user requested testing the public report's wording with only the language
adapted from Korean to Russian:

```text
Always write Russian (and other non-ASCII) strings in tool-call parameters as literal UTF-8; never as `\uXXXX` unicode escapes.
```

`GROMOZEKA_CLAUDE_PROBE_REMINDER_WORDING=tool-utf8` selects that exact sentence
instead of the earlier three-sentence instruction. Only the per-turn reminder
changes: the previous static system instruction remains enabled, with the same
model, effort, inline schema, tools, questions and literal runtime placement.
The initial trial uses the five related Russian questions in one fresh session.
`GROMOZEKA_CLAUDE_PROBE_REMINDER_WORDING=json` retains the earlier wording for
reproduction. Both options are test-only.

All five calls completed, but the new wording did not reliably suppress escapes:

| Turn | Requested sentences | Caller seconds | Output tokens | Unicode escapes |
| --- | ---: | ---: | ---: | ---: |
| 1 | 10 | 13.89 | 675 | 0 |
| 2 | 15 | 16.95 | 869 | 0 |
| 3 | 20 | 25.85 | 1,143 | 0 |
| 4 | 12 | 74.58 | 4,772 | 1,106 |
| 5 | 18 | 101.96 | 6,353 | 1,491 |

The raw stdin confirms the requested sentence inside literal reminder tags,
after the transcript. The static system prompt is byte-identical to the prior
runtime-reminder arm. Every call used one main model API request, with no
schema errors or retries; every warm call used `RESUME_WITH_DELTA` with one
new message. All answers contained exactly the requested numbered sentence
count. This is one five-turn session, not a claim about every model or prompt.

The real probe and all 41 focused runtime/cache/diagnostics tests passed;
the probe asserts functional completion, not successful mitigation. No
production behavior was changed and no commit was made. The probe process
exited normally and no capture process remained.

Evidence: `logs/claude-latency-tool-utf8-result.xml`,
`logs/claude-latency-tool-utf8-summary.jsonl`, `logs/claude-latency-tool-utf8.log`,
and `/tmp/gromozeka-claude-tool-utf8.FRJcTY`.

### Historical JSON and XML formats

The user's recollection of XML is correct. Commit
`5221757e7d73f84a29f911a54b3f68bcfa50559a` (2025-08-10) introduced four
switchable formats: JSON, structured XML, inline XML and plain text. The
structured XML template used `<response><visual>…</visual><voice tone="…">…</voice></response>`;
inline XML used `<tts tone="…">…</tts>` inside ordinary visible text.
Those templates were removed in
`feeb71f70b63b06e2b86598db8f62c21b6b385ac` (2025-11-16), a prompt-system
reorganization. The inspected change does not establish a failure of XML.

Earlier JSON prompt enforcement in `74baa111d405df3332c62fbbdbfb68aebb160296`
(2025-08-06) required all fields, supplied an XML-delimited JSON example, and
said that the output would be parsed directly without preprocessing. Its
commit message records removal of an unsupported assistant-prefill attempt.
Focused history searches of prompt resources and Claude-related sources did
not find an old literal-Unicode workaround. A broader all-file pickaxe scan
was stopped before completion; this is not an exhaustive negative result.

The July 2026 action-protocol fixes addressed different concerns:
`2befad047` explicitly separated external Gromozeka actions from native
Claude Code tools; `502b70b4c` made the selected response branch's payload
required; `2ea8cbf39` nested the response union. Those requirements remain
useful independently of how the model emits the JSON object. Returning to
prompt-directed JSON need not remove action ownership or branch validation.

### Current SDK implementation and CLI flags

Inspected official dependencies locally under `.sources/`, without installing
them into the application or updating the user's CLI:

- TypeScript `@anthropic-ai/claude-agent-sdk` **0.3.263**, the current npm
  version at inspection. In `package/sdk.mjs`, `query()` extracts
  `options.outputFormat.schema`, and `ProcessTransport.initialize()` passes
  it to the CLI as `--json-schema`. The transport uses streaming JSON input
  and output; the output path parses received JSON lines. No model-output
  Unicode rewrite was found in this inspected path. The package targets CLI
  2.1.263, while the live measurements here use installed CLI 2.1.260.
- Python SDK at commit **efd4d865ef1795daffee3cd24cce45307aed8a51**.
  [`subprocess_cli.py`, lines 770–779](https://github.com/anthropics/claude-agent-sdk-python/blob/efd4d865ef1795daffee3cd24cce45307aed8a51/src/claude_agent_sdk/_internal/transport/subprocess_cli.py#L770-L779)
  translates `output_format.type == "json_schema"` into
  `--json-schema` plus `json.dumps(schema)`. This serializes the input schema;
  it is not a repair of Unicode escapes generated in the model's answer.

Neither inspected transport enables `--bare` or `--safe-mode` by default.
The SDKs have additional control-protocol, permissions and lifecycle handling;
this inspection establishes a shared structured-output path, not full
equivalence of every SDK behavior. No separate live SDK benchmark was run.

Installed CLI **2.1.260** documents both `--bare` and `--safe-mode` in `--help`:

- `--safe-mode` disables customizations, including hooks, MCP discovery and
  output styles, while preserving normal authentication and managed policy.
  It is still a supported isolation option for the current Gromozeka design.
- `--bare` skips more startup work, including keychain reads, and requires
  `ANTHROPIC_API_KEY` or an explicit `apiKeyHelper` for first-party Anthropic
  authentication. OAuth/keychain authentication is not read in that mode.
  Third-party providers retain their own credential mechanisms. Therefore it
  is not a drop-in replacement for the user's Claude Code login. It was not
  enabled, and no credentials or billing route were changed.

The [CLI reference](https://code.claude.com/docs/en/cli-reference) still lists
the streaming, schema, effort, tools and customization flags used by this
integration. The [latest changelog](https://raw.githubusercontent.com/anthropics/claude-code/main/CHANGELOG.md)
lists 2.1.263 with generic reliability improvements; it does not establish
that the reproduced Unicode inflation is fixed. No claim is made about the
newer binary's live performance without measuring it.

### Prompt-directed JSON without CLI structured output

`GROMOZEKA_CLAUDE_PROBE_JSON_MODE=prompt` is a test-only executor decorator.
It removes `command.jsonSchema`, places the same schema in the static system
prompt, and changes the protocol instruction from structured-output submission
to plain JSON text. It requires one JSON object without Markdown fences.
Normal production behavior remains unchanged; `cli` is the probe default.

The actual `ClaudeCodeToolProtocol.wrapperRoot` already parses `result` as JSON
when `structured_output` is absent. The experiment therefore uses the normal
runtime answer/tool parser, not a shim that invents a structured response.
The probe additionally validates exact envelope and branch fields, field types,
the five inline response fields, and two string suggestions. It rejects code
fences and malformed JSON instead of stripping or repairing them.

The first five-turn trial retained the prior static Unicode instruction and
exact per-turn UTF-8 reminder, changing only the output mechanism/instructions:

| Turn | Requested sentences | Caller seconds | Output tokens | Text Unicode escapes |
| --- | ---: | ---: | ---: | ---: |
| 1 | 10 | 17.70 | 824 | 0 |
| 2 | 15 | 20.17 | 1,029 | 0 |
| 3 | 20 | 27.62 | 1,354 | 0 |
| 4 | 12 | 22.25 | 1,048 | 0 |
| 5 | 18 | 30.60 | 1,292 | 0 |

All five JSON payloads passed strict checks and exact sentence-count inspection.
Each call had one main model request and no retries or tool errors. The first
text arrived in 0.74–3.53 seconds. This is a promising candidate, not proof of
reliability over arbitrary conversations or confirmation of the Ubuntu cause.

The summary script now counts escapes separately in `text_delta` as
`textUnicodeEscapes`; the older `unicodeEscapes` field counts only
`input_json_delta`. Zero in the latter is not evidence about plain JSON text.
Both counters operate after parsing the outer event JSON, and skip literal
escaped backslashes. They are not counts of log-file JSON escaping.

Evidence: `logs/claude-latency-prompt-json-result.xml`,
`logs/claude-latency-prompt-json-summary.jsonl`, and
`/tmp/gromozeka-claude-prompt-json.pUUroA`.

A follow-up trial removes both Unicode instructions, uses ten consecutive
dialogue turns, then a separate ten-turn inline dialogue with one synthetic
external action per turn and a Russian 10–20-sentence final answer. Tool keys
are Cyrillic (`образец-N`). The probe validates the action name, exact argument
object, resumed follow-up, and final inline payload. No real tool executes;
the fixture supplies `synthetic-status=ready` as the result.

Both ten-turn sessions completed successfully, with no Unicode instructions:

| Measurement | Observed range |
| --- | ---: |
| Ordinary inline answer, caller time | 12.23–22.72 s |
| External action request only, CLI round trip | 1.58–3.24 s |
| Inline final answer after tool result, CLI round trip | 13.68–26.30 s |
| Complete tool request plus final answer, caller time | 15.85–28.15 s |

The ten ordinary caller times were 15.005, 16.374, 22.723, 18.065, 21.028,
12.234, 15.423, 18.545, 14.002 and 16.704 seconds. The ten complete tool-cycle
times were 15.845, 18.627, 27.072, 21.705, 28.152, 15.909, 21.464, 25.207,
21.594 and 24.846 seconds. Each cycle contains two intended model requests:
the action selection and the final answer after the supplied result.

All 30 CLI responses in this follow-up passed the strict fixture checks:
10 plain-dialogue final answers, 10 action requests and 10 post-tool final
answers. There were 30 main model API message IDs, zero retries, zero tool
errors, zero `textUnicodeEscapes`, and no native `StructuredOutput` calls.
Every warm call used the persisted session; action arguments retained the
exact requested Cyrillic key. All 20 final answers had the exact requested
10/15/20/12/18 numbered sentence count and two inline suggestions. The maximum
gap between received events was 2.316 seconds.

Together with the initial five-turn trial, this candidate has 25 validated
long answers and 10 validated synthetic external-action requests. This is
bounded component evidence on one model/account/platform, not an estimate of
arbitrary-schema reliability, a new live XML benchmark, or an Ubuntu test.
The current result gives no performance reason to replace the entire response
format with XML before trying the smaller JSON-emission change.

The real probe plus 41 focused unit tests passed (18 runtime, 17 process-cache,
6 diagnostics). The summary counter also passed seven local checks covering
literal Cyrillic, valid/invalid/incomplete escapes and escaped backslashes.
`git diff --check` and both script syntax checks passed. Probe processes exited
normally. No Server, unrelated Worker, global CLI installation, authentication,
production format setting, commit or remote branch was changed.

Evidence: `logs/claude-latency-prompt-json-plain-result.xml`,
`logs/claude-latency-prompt-json-plain-summary.jsonl`,
`logs/claude-latency-prompt-json-plain.log`, and
`/tmp/gromozeka-claude-prompt-json-plain.jEPhqD`.

Recommended next implementation boundary: keep the existing response envelope,
external-action ownership and assistant fields; move format direction into the
Claude provider prompt instead of the CLI flag. Validate before exposing an
answer or executing an action. Decide a small, explicit correction limit for
invalid model output, with no automatic re-execution of completed actions.
The current permissive parser alone is not a replacement for full schema
validation. Test malformed output, required action choices, multiple actions,
tool results and session reuse before enabling the change in normal calls.

## Production implementation

The user approved prompt-directed JSON, the precise short format reminder,
and up to three correction retries before any external action executes.
The normal Claude Code provider now implements that contract:

- `ClaudeCodeCommand` and process launch arguments no longer have a JSON
  schema option. The schema is part of the system prompt, whose fingerprint
  already governs process-cache compatibility. `stream-json` remains the
  transport format. Native web-tool calls retain their dedicated path.
- `ClaudeCodeResponseContract` supplies the schema and the agreed per-call
  reminder, including action ownership and `tool_calls`/`final_answer` branches
  when external actions are available. Tool-result continuations and format
  corrections receive the same short reminder. It is not stored as a User
  conversation message. No Unicode-specific instruction is added.
- Strict JSON parsing precedes local JSON Schema validation through
  `io.github.optimumcode:json-schema-validator:0.5.5` (MIT). The dependency's
  isolated loader resolves registered/local references without network fetches.
  Every external action's argument object is also checked against that action's
  own input schema. No part of an invalid action batch reaches execution.
- One original model response may be followed by at most three corrective
  responses. The correction supplies validation errors, not another copy of
  the entire schema, input history or attachments. There is no retry delay.
  Transport errors and cancellation are not treated as format failures.
- A process lease encloses all attempts, including non-persistent calls. Such
  calls still use `--no-session-persistence`; no temporary disk session is
  introduced to support corrections. Failed validation evicts the leased
  process and clears its persisted Gromozeka session state after exhaustion.
- Usage totals include failed format attempts; context usage uses the final
  provider snapshot. Compaction boundaries across attempts are preserved.
  Failed answer text and failed action requests are not appended to Gromozeka
  conversation history. Cancellation and caller timeout cover the entire loop.
- Ordinary logs contain correlation IDs, timing, validation result/count and
  separate Unicode-escape counters for tool JSON and text deltas. They do not
  contain the response, argument values or detailed validation errors.

The latency fixture now exercises the normal implementation, not the earlier
test-only output-mode decorator. Historical `GROMOZEKA_CLAUDE_PROBE_JSON_MODE`
and Unicode-reminder switches were removed along with that decorator.

Focused unit coverage includes malformed JSON and Markdown fences, multiple
objects, scalar roots, nested type/required/array constraints, local references,
unknown actions, required tool choice, invalid arguments inside a batch, all
three correction attempts, usage aggregation, resumed tool results, dependent
actions, cancellation, timeout, ephemeral process lifetime and cache eviction.
These tests use synthetic responses and never execute real actions.

Final verification:

- 169 `:infrastructure-ai:jvmTest` cases, all passed.
- 9 focused `WorkerRequestServiceTest` cases, all passed.
- 45 `:worker:test` cases, all passed.
- `git diff --check` and both diagnostic script syntax checks passed.
- An extra syntax regression demonstrated that `kotlinx.serialization` alone
  accepts a leading-zero number such as `02`. The contract now first uses the
  existing Jackson parser with trailing-token and duplicate-key rejection,
  then validates the Kotlin JSON tree against the schema. Number-syntax and
  duplicate-key regression tests pass.
- The final validator revalidated all 18 successful captured CLI responses
  from the production-path live run, without any new provider calls.

Production-path live results (same CLI 2.1.260, Opus 5, medium, Russian):

| Case | Completed | Observed time |
| --- | ---: | ---: |
| Consecutive inline answers | 10 | 12.34–27.31 s CLI round trip |
| External action selections | 4 | 1.64–2.30 s CLI round trip |
| Inline answers after tool results | 4 | 13.59–28.41 s CLI round trip |
| Complete tool-plus-answer cycles | 4 | 15.95–30.06 s caller time |

All completed responses passed on the first attempt: no format corrections,
Unicode escapes, schema errors, or duplicate main model requests. Final
answers retained the requested numbered sentence counts and two inline
suggestions. The fifth tool-cycle request received HTTP 429 with the CLI's
session-quota message and a 15:30 Asia/Jerusalem reset time. The remaining six
planned cycles were not completed, and no quota bypass or billing change was
attempted. The live JUnit failure records that external limit, not a successful
twenty-turn run; the final offline test suite is separately green.

Evidence: `logs/claude-json-release-live-result.xml`,
`logs/claude-json-release-live-summary.jsonl`,
`logs/claude-json-final-verification.log`, and
`/tmp/gromozeka-claude-json-release.QOY3H1`.

Release preflight: remote `main` matched local `240ba1910` before the change;
the latest published remote release was `v3.0.0`. This is an internal provider
fix, so the requested release uses the workflow's patch generator with
`publish_release=true` and `deploy_aws=false`. Do not push a tag manually:
the tag-push workflow path enables deployment.
