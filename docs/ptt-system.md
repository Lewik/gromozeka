# Push-To-Talk And Speech Transcription

## User Model

Speech input has two independent choices:

- the audio source is either the current client or one exact Worker audio input;
- the transcription engine is OpenAI API or local Whisper.

An offline Worker remains selected and is shown as unavailable. Gromozeka does
not choose another Worker, retry on another machine, or silently change the
transcription engine.

The microphone control describes capture, not background recognition. Its visible
state is one of `IDLE`, `PREPARING`, `RECORDING`, or `FINALIZING`. Once captured
audio has been queued, the control returns to `IDLE`, so the next recording can
start while earlier speech is still being transcribed.

## Interaction Behavior

The on-screen microphone is a bounded tap-to-record control:

- the first tap starts preparing and then recording;
- a tap while preparing cancels the request;
- a tap while recording stops and finalizes capture;
- finalized audio enters the transcription queue independently of the next capture.

A status surface above the composer mirrors `PREPARING`, `RECORDING`,
`FINALIZING`, and capture errors. The surface is itself tappable while preparing
or recording. Supported clients provide distinct haptic feedback when recording
starts, recording stops, or capture fails.

External PTT controls retain the richer gesture contract: a single click stops
speech playback, a double click interrupts the current conversation turn, a
hold records speech, and a click followed by a hold interrupts before recording.
The iOS Action Button reports an explicit hold immediately because its system
event already distinguishes press from release.

## Ordering And Concurrency

By default, one client-wide transcription lane preserves capture and delivery
order across all tabs. A failed or closed-tab delivery does not block later
recordings.

The device-local **Independent voice transcription across tabs** setting creates
one sequential lane per tab. Different tabs may then finish in either order,
while recordings addressed to the same tab remain ordered. A mode change takes
effect after the currently active lanes drain, so it cannot reorder in-flight
audio.

Local Whisper has a separate maximum-concurrent-transcriptions setting. Its
default is one. This limit controls actual Whisper execution when multiple
transcription lanes or other speech features submit work concurrently.

## Routing

```text
Client PTT
  |-- current-client source -> client recorder
  `-- Worker source -> Server -> exact Worker audio session -> captured WAV

captured recording -> client transcription queue -> Server speech route
                                             |-- OpenAI API
                                             `-- local Whisper on Server or exact Worker
```

The Server owns Worker capture sessions and binds each session to the
authenticated user and client connection. Disconnecting that client cancels its
sessions. The Worker owns the operating-system microphone handle and enforces a
maximum capture duration.

Worker audio is captured as mono 16 kHz signed 16-bit PCM and returned as WAV
through the authenticated Server protocol. The Worker advertises the inputs it
can currently discover. Failure to inspect the host audio subsystem produces an
empty input list instead of preventing the Worker from starting.

## Ownership Boundaries

- `RemotePttController` owns gestures and the visible capture lifecycle.
- `VoiceTranscriptionQueue` owns ordering, background recognition, and delivery
  to the tab captured at recording start.
- `SpeechCaptureApplicationService` validates routes and owns client-bound
  Server capture sessions.
- `WorkerAudioCaptureService` owns Worker microphone sessions.
- `SttService` routes finite transcription to the configured execution target.
- Local Whisper services enforce the configured execution concurrency.

Worker audio never bypasses the Server. Capture control, audio, transcription,
and results use authenticated protocols.

## Verification

Focused tests cover codec round trips, capture ownership, starting a new
recording while prior transcription is pending, global FIFO order, independent
tab lanes, same-tab order, failures that release the queue, and Local Whisper
concurrency. Real microphone verification remains a manual smoke test because
permissions, devices, and host audio stacks are platform-specific.
