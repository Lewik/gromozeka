# Computer Use

> Temporary design note. Delete after Computer Use is implemented and durable contracts are documented elsewhere.

## Goal

Allow a model to operate an interactive desktop on an explicitly selected Worker when browser or application-specific APIs are insufficient.

Unlike Browser Use, Computer Use is expected to take visible control:

- screenshots observe the actual desktop;
- pointer, keyboard, scrolling, and window switching affect the user's interactive session;
- focus changes are part of the feature rather than something to hide;
- the user can pause, take over, and return control;
- no action is automatically retried when its outcome is unknown.

## Distinction From Browser Use

Use Browser Use for web pages whenever possible. It offers semantic DOM state, stable element references, and background operation.

Use Computer Use for:

- native applications;
- remote desktop/VDI content that is only visible as pixels;
- browser surfaces inaccessible through the browser bridge;
- OS dialogs and cross-application workflows;
- visual tasks where no structured automation API exists.

Computer Use can operate a browser as pixels, but this is a fallback, not the preferred browser architecture.

## Execution Topology

Computer Use belongs to a Worker's interactive OS session.

```text
Conversation runtime on Server
        |
        | exact Worker + ComputerUseSessionId
        v
Worker Computer Controller
        |
        +-- capture backend
        +-- pointer/keyboard backend
        +-- window/display inventory
        `-- local interactive desktop
```

Server owns orchestration and persists session state. Worker owns OS handles and executes observations/actions. No client sends input directly to Worker.

A headless Worker, locked desktop, disconnected VDI session, or missing OS permission means Computer Use is unavailable. The capability must be advertised honestly.

## Session Model

```kotlin
data class ComputerUseSession(
    val id: ComputerUseSessionId,
    val conversationId: Conversation.Id,
    val workerId: WorkerId,
    val displayId: DisplayId,
    val state: ComputerUseSessionState,
    val controlOwner: ComputerControlOwner,
    val lastObservationArtifactId: ArtifactId?,
    val createdAt: Instant,
    val lastActivityAt: Instant,
)

enum class ComputerUseSessionState {
    STARTING,
    READY,
    MODEL_ACTIVE,
    USER_ACTIVE,
    PAUSED,
    DISCONNECTED,
    UNKNOWN,
    CLOSED,
}
```

One interactive display should have at most one mutating Computer Use session. This is an explicit resource claim, not an auto-expiring queue lease:

- no second model silently controls the same pointer;
- losing network does not grant control elsewhere;
- reconnect reconciles local and Server session identity;
- an uncertain interrupted action remains uncertain;
- user or model explicitly closes/releases the session.

The claim may have stale-session administration tools, but no time-based automatic reassignment while work may still be running.

## Observe-Act Loop

The core loop is:

1. Worker captures the selected display/window.
2. Server stores the observation as an Artifact and sends a model-sized image block to the LLM.
3. Model returns one or more bounded input actions.
4. Worker executes actions in order.
5. Worker reports exact completion or an unknown/failed outcome.
6. Runtime schedules the next observation only when required.

Screenshots are immutable observations with Worker, display, dimensions, scale, cursor, timestamp, and optional active-window metadata.

Do not continuously stream full-resolution frames through conversation history. Keep original observations as Artifacts when useful, provide a bounded current image to the model, and summarize/coalesce intermediate frames.

## Coordinate System

Computer Use must define coordinates precisely across Retina/HiDPI and multi-display setups.

Each observation includes:

- pixel width and height of the returned image;
- logical display bounds;
- scale factor;
- display origin in the Worker's virtual desktop;
- crop/window bounds when applicable;
- image orientation;
- cursor inclusion state.

Actions use normalized observation coordinates or explicitly named image-pixel coordinates. Worker performs the only conversion to OS coordinates. The model must never guess whether coordinates are logical points or physical pixels.

If display geometry changes, prior coordinate references become invalid and the next action must require a fresh observation.

## Tool Surface

Session tools:

- `grz_computer_targets`
- `grz_computer_open_session`
- `grz_computer_sessions`
- `grz_computer_pause_session`
- `grz_computer_resume_session`
- `grz_computer_close_session`
- `grz_computer_observe`

Action tool:

```text
grz_computer_act
```

Suggested bounded actions:

- move pointer;
- left/right/middle click;
- double click;
- pointer down/up;
- drag;
- scroll;
- type text;
- key press/chord;
- wait;
- optionally activate a known window.

The first implementation should avoid arbitrary embedded scripts in the action protocol. The Worker already has shell tools when scripting is the correct abstraction.

Each action batch has a unique ID. Worker deduplicates the exact delivery ID only to avoid executing the same accepted batch twice during transport acknowledgement. It does not invent a new attempt after a failed or unknown execution.

## User Handoff

The user must always be able to take control locally or from Gromozeka UI:

- any physical mouse/keyboard activity can switch the session to `USER_ACTIVE`;
- model mutations pause, but observations may continue if useful;
- Runtime shows that the user owns control;
- an explicit Continue button returns control to the model;
- Stop closes the session and cancels pending actions where possible;
- emergency input interruption must be local and immediate, not wait for the model.

Automatic user-activity detection is platform-dependent and may be imperfect. Therefore the UI also needs explicit Pause/Continue/Stop controls.

## Platform Backends

### macOS

- Screen Recording permission for capture.
- Accessibility permission for pointer/keyboard/window control.
- CoreGraphics/ScreenCaptureKit for observation where practical.
- CGEvent or an equivalent supported input path for actions.

### Windows

- Windows Graphics Capture/Desktop Duplication for observation.
- SendInput/UI Automation where appropriate for input and window metadata.
- Session-zero services cannot control a normal user's desktop; Worker must run in the interactive user session.

### Linux

- Wayland support depends on compositor/portal capabilities and may require visible consent.
- X11 can use established capture/input APIs but must advertise actual availability.
- VDI environments may expose a different display/session than the login shell; Worker profile must report this clearly.

Capabilities are discovered at Worker startup and refreshed by the Worker environment tool. Missing permission is an explicit unavailable reason, not a generic tool failure.

## Focus and Interference

Computer Use is inherently intrusive. Correct behavior is:

- claim one interactive target;
- visibly report model control;
- bring windows forward when actions require it;
- never promise background operation;
- pause promptly on user takeover;
- avoid running concurrent input sequences against the same desktop.

For macOS APIs that can manipulate some windows without focus, that is an implementation optimization. It does not redefine Computer Use as non-intrusive Browser Use.

## Failures and Reconnection

Failure categories must remain distinct:

- unsupported platform/backend;
- permission denied;
- desktop locked or unavailable;
- display/window disappeared;
- Worker disconnected before action acceptance;
- Worker accepted action but final outcome is unknown;
- action definitely failed before mutation;
- user interrupted execution.

Only a transport delivery that Worker proves was never accepted may be resent with the same action ID. Never automatically replay a click, keystroke, drag, or submission after acceptance or unknown outcome.

On reconnect Worker reports:

- local active Computer Use session ID;
- display geometry;
- last accepted and last completed action IDs;
- whether any action outcome is unknown;
- current lock/permission state.

Server reconciles and notifies the model. It does not restart the loop by itself.

## Runtime UI

Runtime panel should show:

- selected Worker and display;
- session state and control owner;
- latest observation thumbnail;
- latest action summary;
- paused/disconnected/permission state;
- Take control, Continue, Pause, Stop;
- Open latest screenshot.

A future live preview may stream low-rate thumbnails outside conversation history. It must remain Server-mediated and use backpressure/coalescing.

## Artifact Integration

All observations use the common Artifact pipeline:

- original capture stored once when retention is needed;
- model-sized derivative generated for LLM input;
- UI thumbnail derived separately;
- tool result references Artifact ID;
- no Worker-local screenshot path enters conversation state;
- GC follows conversation/tool-result references.

The standalone screenshot tool is the first reusable part of this subsystem. Computer Use later adds session ownership, coordinate metadata, and input actions around it.

## Security Position

Computer Use is a trusted, unsandboxed Worker capability. Gromozeka does not add per-action approvals, application denylists, or a second OS permission model.

The operator controls exposure through:

- the OS account running Worker;
- OS capture/accessibility permissions;
- machine/VM isolation;
- credential scope;
- network policy and backups;
- which Workers advertise Computer Use.

Gromozeka still provides normal in-contour security: authenticated encrypted channels, exact Worker routing, session ownership, artifact authorization, audit-friendly state transitions, and visible control status.

## Implementation Sequence

1. Complete common Artifacts and the Worker screenshot tool.
2. Add display inventory, coordinate metadata, and capture capability advertisement.
3. Prototype observe plus one bounded click/type action on macOS.
4. Add `ComputerUseSession` exclusive claim and user/model handoff.
5. Add Windows backend.
6. Add Linux backend based on actual target environments.
7. Add Runtime UI and low-rate preview.
8. Add provider-specific Computer Use message formats only behind the same domain action/observation contract.

## Verification

- Retina/HiDPI click lands on the intended pixel;
- multiple displays preserve origins and scale;
- user input pauses model actions;
- two conversations cannot mutate the same display concurrently;
- Worker disconnect never causes replay or reassignment;
- locked desktop and missing permissions are explicit;
- reconnect reconciles action IDs and unknown outcomes;
- screenshot artifacts render on another client;
- Stop prevents further queued actions;
- macOS, Windows, and Linux advertise only capabilities that actually work.

## Open Questions

- Is exclusivity per Worker, per display, or per interactive OS session on each supported platform?
- Should ordinary user input always take control, or only input after a configurable grace interval?
- Which observations deserve durable retention versus short-lived cache artifacts?
- Should the model receive the cursor in screenshots by default?
- How should remote desktop disconnect/reconnect map to session state on corporate VDIs?
