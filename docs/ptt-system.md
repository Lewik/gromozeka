# Push-To-Talk And Speech Transcription

## User Model

Speech input has two independent choices:

- the audio source is either the current client or one exact Worker audio input;
- the transcription engine is OpenAI API, local Whisper, or an opt-in Claude Code connection.

An offline Worker remains selected and is shown as unavailable. Gromozeka does
not choose another Worker, retry on another machine, or silently change the
transcription engine.

The microphone button is disabled with an explanation when the configured route
cannot run. Its visible state is one of `IDLE`, `PREPARING`, `RECORDING`, or
`TRANSCRIBING`.

## Interaction Behavior

The on-screen microphone is a bounded tap-to-record control:

- the first tap starts preparing and then recording;
- a tap while preparing cancels the request;
- a tap while recording stops capture and starts transcription;
- the control is disabled while transcription is running.

A status surface above the composer mirrors `PREPARING`, `RECORDING`,
`TRANSCRIBING`, and capture errors. The surface is itself tappable while
preparing or recording. Supported clients provide distinct haptic feedback
when recording starts, recording stops, or capture fails.

External PTT controls retain the richer gesture contract: a single click stops
speech playback, a double click interrupts the current conversation turn, a
hold records speech, and a click followed by a hold interrupts before recording.
The iOS Action Button reports an explicit hold immediately because its system
event already distinguishes press from release.

## Routing

```text
Client PTT
  |-- current-client source -> client recorder -> Server transcription route
  `-- Worker source -> Server -> exact Worker audio session
                               |-- record WAV -> configured transcription target
                               `-- direct Claude Code microphone transcription
```

The Server owns capture sessions and binds each session to the authenticated
user and client connection. Disconnecting that client cancels its sessions.
The Worker owns the operating-system microphone handle and enforces a maximum
capture duration.

Worker audio is captured as mono 16 kHz signed 16-bit PCM and returned as WAV.
The Worker advertises the inputs it can currently discover. Failure to inspect
the host audio subsystem produces an empty input list instead of preventing the
Worker from starting.

## Claude Code Voice

Claude Code transcription is an optional adapter around a separately installed
and authenticated Claude Code executable. Gromozeka does not redistribute
Claude Code. The adapter controls the documented interactive voice UI rather
than an Anthropic speech API and is not an Anthropic-supported integration.
Operators must enable it explicitly and confirm that their organization and
account terms permit automated use. In particular, it must not be enabled for
an individual Claude.ai Pro or Max account without explicit permission from
Anthropic.

The adapter keeps one isolated, tool-free Claude PTY warm for the selected
Worker connection and language. The warm process does not enter voice mode and
does not open the microphone. Availability checks prepare it before the user
presses PTT; starting a recording consumes that process and immediately warms
its replacement. It runs in an empty application-owned directory with Claude
tools disabled. On first use the adapter recognizes Claude's workspace-trust
screen and confirms only that stable service directory; user workspaces remain
untouched. The adapter waits until Claude's terminal reports both `REC`
and `tap to send` before reporting `RECORDING`.
Current Claude Code versions buffer captured audio while their speech WebSocket
connects, so network setup does not require an artificial client delay. On stop,
the second tap finalizes transcription and Claude automatically submits the
transcript when it contains at least three words. A `UserPromptSubmit` hook
captures that text and blocks any LLM turn. For shorter transcripts, the adapter
waits until Claude's visible processing output becomes stable and submits the
prepared input once after the normal automatic-submit path has had time to
complete. It does not inspect private Claude Code debug events.

Direct microphone capture is supported on macOS, Linux, and Windows when the
installed Claude Code build supports voice on that host. Windows currently uses
only the Worker's system-default local microphone.

Forwarding already recorded audio into Claude Code currently requires a Linux
target with `pactl` and `paplay`. Gromozeka creates a temporary PulseAudio null
sink, plays the WAV into its monitor source, and removes the sink afterward.
The route is reported as unavailable before capture when the selected Worker
does not advertise the required executables.

## Ownership Boundaries

- `RemotePttController` owns client gesture and visible capture state.
- `SpeechCaptureApplicationService` validates the selected route and owns
  client-bound Server sessions.
- `WorkerAudioCaptureService` owns Worker microphone and Claude PTY sessions.
- `ClaudeCodeVoiceTranscriptionService` owns Claude voice process integration.
- `SttService` routes finite transcription to the configured execution target.

No audio capture travels directly from a Worker to a client. All control and
results pass through the Server and the authenticated remote protocol.

## Verification

Codec, route ownership, platform restrictions, and Claude terminal parsing are
covered by focused tests. Real microphone verification remains a manual smoke
test because permissions, devices, and host audio stacks are platform-specific.
