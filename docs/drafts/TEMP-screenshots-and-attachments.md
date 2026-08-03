# Screenshots and Message Attachments

> Temporary design note. Delete this file after the described work is implemented and the durable parts are reflected in the development guide and domain documentation.

## Goal

Give both the user and the model a coherent way to add visual and file context to a conversation:

- the user can attach files, paste images, drag files into the composer, or capture/select a screenshot;
- the model can capture a screen on an explicitly selected Worker;
- screenshots and ordinary files use one storage, transport, lifecycle, and conversation model;
- no local filesystem path is mistaken for content that another client, Server, Worker, or model provider can access.

This is the first practical slice of the larger Browser Use and Computer Use direction. Browser-page screenshots produced by a future browser session should eventually use the same artifact pipeline, but browser automation itself is out of scope here.

## Starting State

The current screenshot UI is misleading rather than partially functional:

- `MessageInput` always renders a camera button.
- `TabViewModel.captureAndAddToInput()` asks a `ScreenCaptureController` for a local path and appends that path to the text input.
- every remote client currently receives a no-op `ScreenCaptureController`, so web, iOS, Android, and the current remote JVM client get no screenshot.
- the old macOS implementation invokes `screencapture` and returns a path on the machine that ran the capture. Even if wired, that path is not an attachment and is not portable to Server, another client, a Worker, or an external provider.
- user messages are currently created with one text-only `UserMessage` content item.
- the domain already contains image and file-shaped content types, but there is no complete artifact storage/upload lifecycle behind them.
- MCP image/audio/resource results are currently flattened into strings such as `[Image: image/png]`, so the existing tool callback boundary loses binary content.

The camera button should not remain wired to this path-based behavior.

## Implemented Slice

The first end-to-end slice is now implemented locally:

- `Artifact` metadata is stored in PostgreSQL and immutable bytes are stored under `${GROMOZEKA_HOME}/artifacts` through the `ArtifactContentStore` domain port.
- Authenticated Server HTTP routes upload, download, and delete draft artifacts. Conversation and project ownership is checked on every operation.
- Upload creates `DRAFT`; accepting a user message, queued message, or typed tool result validates and commits its references before provider materialization.
- Draft garbage collection runs at Server startup and at a low-frequency interval. It removes expired draft records and content bytes that have no metadata record.
- User messages contain canonical `Artifact.Reference` items. Tool binary results are persisted by Server and converted to canonical artifact references before entering conversation history.
- The composer supports attachment-only messages, up to 10 files, 25 MB per file, and 50 MB total per message.
- Web supports multiple file selection, clipboard image/file paste, file drop, and one-frame `getDisplayMedia()` capture.
- JVM supports multiple file selection and drag/drop. macOS opens the OS screenshot selector; Windows and Linux use full-desktop capture when a graphical desktop is available.
- Native iOS supports document selection and a screenshot-filtered Photos picker. Native Android supports document/image selection and intentionally does not advertise screenshot capture yet.
- Sent image attachments and image tool results render as lazy previews. Preview bytes are fetched from Server and held in a bounded client memory cache.
- OpenAI Responses, OpenAI Chat, OpenAI subscription, Anthropic, and Claude Code provider paths materialize committed image/file artifacts instead of receiving local paths or placeholder strings.
- `grz_capture_screenshot` is an exact-Worker inspection tool. It captures that Worker's complete visible desktop, bounds the long edge to 1024-4096 pixels, returns PNG bytes, and is advertised only when capture is available.
- Worker binary tool results travel only through the authenticated Worker gateway to Server. No Worker sends artifacts directly to a client.
- Forking a conversation clones its committed artifacts into the new conversation scope.

The live web check covered a real picker upload and draft removal. Focused domain, application, provider, Worker-tool, Server, and multiplatform presentation compilation/tests cover message commit and materialization paths without sending a real LLM request.

The following improvements are deliberately not disguised as complete:

- upload/download currently use bounded in-memory byte arrays rather than streaming bodies;
- the composer shows aggregate busy/error state, not per-file byte progress and retry controls;
- upload commit and runtime-task acceptance are not one database transaction. A failed acceptance can retain a committed but unreferenced artifact; durable reference rows are the clean future fix;
- image inputs are size-bounded by encoded bytes, but decoded pixel/dimension validation still needs a format-aware implementation before accepting hostile public uploads;
- committed artifact reference counting/message-deletion retention is deferred because conversation message deletion does not yet expose a durable reference lifecycle;
- the Worker screenshot tool captures the complete visible desktop only; display/window/region inventory belongs to the later Computer Use session model;
- native Android screenshot capture and richer platform-native preview/download interactions remain separate tasks.

## Product Decisions

### One artifact pipeline

Screenshot capture is an acquisition method, not a special message format. The same pipeline must accept:

- screenshots;
- images selected from Photos or a file picker;
- clipboard images;
- files dropped onto the composer;
- ordinary files selected by the user;
- files or images produced by tools;
- future browser screenshots and Computer Use observations.

All acquisition methods produce an uploaded `Artifact`. A message or tool result references that artifact by ID.

### Two distinct screenshot operations

There are two different features and they must not be conflated:

1. **User screenshot action**: initiated from a visible client button. It may invoke an OS/browser picker and then attach the result to the draft message.
2. **Model screenshot tool**: initiated by the model and executed on an explicitly selected Worker. It captures that Worker's desktop/window and returns an image tool result.

A web client cannot silently act as a model-controlled screenshot Worker. Browser screen capture requires current user activation and an explicit source choice. A future client-action protocol could request that the active client ask the user for a capture, but that is not an automatic tool and is out of the first implementation.

### Generic attachment button remains universal

The composer gets a paperclip/add button on every platform. It opens the best local picker and supports multiple selection.

The screenshot button is capability-driven:

- show it only when that client can perform a meaningful screenshot action;
- never render an enabled button backed by a no-op controller;
- a platform may implement it as capture, screenshot-only selection, or an explicit capture-source picker, but the tooltip must describe the actual behavior.

### Server is the canonical owner

Artifacts are canonically registered and authorized by Server. A client or Worker uploads bytes to Server and receives an `ArtifactId`. No Worker sends data directly to another client, and no conversation stores a Worker-local path as if it were globally meaningful.

The first storage backend is a local Server filesystem/Docker volume. The domain depends on `ArtifactContentStore`, so an S3-compatible implementation can be added without changing conversations or tools.

Large blobs should not live inline in PostgreSQL conversation rows. PostgreSQL stores metadata and references; the artifact store holds bytes.

## Platform Behavior

| Platform | Attach files/images | Screenshot button | Drag/drop and paste |
|---|---|---|---|
| Desktop web | Native file input | `getDisplayMedia()`, user chooses tab/window/screen, capture one frame, stop stream immediately | Both |
| iPhone/iPad PWA | System file/photo picker | Hide initially; web cannot reliably open only Screenshots or silently capture the device | File/photo picker and clipboard where exposed by Safari |
| Native iOS | `PhotosPicker` and document picker | Open `PhotosPicker` filtered to screenshots; it selects an existing screenshot rather than capturing other apps | Native drop can follow later; picker is primary |
| Native Android | Photo picker/document picker | Initially hide or open image picker with honest wording; native screen capture requires a separate consent flow | Picker first; platform drag/drop can follow |
| JVM macOS | Native file picker | OS capture selector for screen/window/region, then upload bytes | Both |
| JVM Windows | Native file picker | Windows capture implementation, then upload bytes | Both |
| JVM Linux | Native file picker | Capability-dependent: portal/Wayland/X11 capture; hide when unavailable | Both where supported |

### Desktop browser details

`navigator.mediaDevices.getDisplayMedia()` makes the web button useful, but only as an explicit user action:

- it requires HTTPS, except trusted localhost development contexts;
- it requires transient user activation, so it must be called directly from the button event path;
- the browser must let the user choose the source every time;
- permission cannot be persisted as permanently granted;
- the page should wait for the first real video frame, draw it to a canvas, encode PNG/WebP, stop every media track, and upload the resulting blob;
- feature detection decides whether the screenshot button is shown. User-agent guessing must not decide behavior.

This supports Chrome/Edge and desktop Safari where implemented. It must not be treated as an iPhone solution.

### Native iOS details

The best first UX is the system `PhotosPicker`:

- it grants Gromozeka access only to the item the user chooses;
- it does not require broad photo-library authorization;
- Apple exposes a screenshots filter, so the camera/screenshot action can open a screenshot-focused picker;
- the generic paperclip can open unrestricted images/files.

PhotoKit can query the Screenshots smart album and identify screenshot assets, but doing this automatically requires photo-library authorization. Automatically fetching the latest screenshot is not worth that broader permission in the first version. It can be reconsidered only if selecting from the screenshot-filtered picker proves too slow.

### Browser page screenshots are separate

When Browser Use is implemented, Playwright/CDP can capture the controlled page without using `getDisplayMedia()`. That screenshot belongs to the Browser Session and should be persisted as an Artifact. It does not make the ordinary web client an automatic desktop screenshot source.

## Domain Model

Names are provisional but the boundaries should remain.

```kotlin
@JvmInline
value class ArtifactId(val value: String)

data class Artifact(
    val id: ArtifactId,
    val ownerId: UserId,
    val projectId: Project.Id?,
    val conversationId: Conversation.Id?,
    val originalFileName: String?,
    val mediaType: MediaType,
    val sizeBytes: Long,
    val sha256: String,
    val storageKey: String,
    val purpose: ArtifactPurpose,
    val state: ArtifactState,
    val createdAt: Instant,
)

enum class ArtifactPurpose {
    MESSAGE_ATTACHMENT,
    TOOL_RESULT,
    SCREENSHOT,
    BROWSER_OBSERVATION,
}

enum class ArtifactState {
    DRAFT,
    COMMITTED,
    DELETED,
}
```

`Artifact` describes stored immutable bytes. A message attachment is a reference to it, not another copy of the blob:

```kotlin
data class AttachmentItem(
    val artifactId: ArtifactId,
    val mediaType: MediaType,
    val displayName: String?,
    val kind: AttachmentKind,
) : Conversation.Message.ContentItem()
```

The current `ImageItem`, `ToolResult.Data.FileData`, and `ToolResult.Data.Base64Data` should be reconciled rather than expanded with another parallel family. A likely clean result is:

- one artifact reference type usable in user messages and tool results;
- optional inline data only at provider protocol boundaries, not as canonical conversation storage;
- explicit image metadata when rendering a thumbnail or mapping to a multimodal provider block.

Artifact content is immutable. Editing a file means uploading a new artifact. Deduplication by hash may be used inside one ownership scope, but authorization must remain attached to logical artifact records so hashes do not leak cross-user existence.

## Storage and Transport

### Client upload

Use an authenticated HTTP streaming upload rather than embedding base64 in the conversation WebSocket:

1. Client creates a draft upload with metadata.
2. Client streams bytes to Server.
3. Server validates limits, computes the hash, stores bytes, and returns `ArtifactId` plus normalized metadata.
4. Composer holds a `PendingAttachment` referencing the draft artifact.
5. Sending or queueing the message commits the attachment references together with the message.

HTTP is preferable for large payloads because it avoids blocking the real-time control/event channel and naturally supports progress, cancellation, body limits, and future direct object-storage uploads.

Required operations:

- create/upload artifact;
- fetch authorized metadata;
- download/stream authorized content;
- delete an uncommitted draft;
- commit references as part of message acceptance.

### Worker upload

The Worker capture executor returns bytes and metadata through the authenticated Worker gateway. Server stores them and converts completion into a typed tool result containing the artifact reference. The client learns about it only through normal Server conversation updates.

Do not return a path such as `/tmp/screenshot.png` from a Worker tool. It is useful only on that Worker and becomes invalid after cleanup or restart.

### Provider materialization

Before an LLM request, each provider adapter materializes supported attachments from canonical artifacts:

- images become native image input blocks;
- providers that require base64 receive bounded base64 generated at request time;
- providers with file-upload APIs may cache provider file IDs as derived transport metadata;
- Claude Code running on a Worker receives native inline image/document content blocks through its stream-JSON input. A future staged-file transport may be added for formats or sizes that the CLI cannot accept inline;
- unsupported attachment kinds fail visibly or are converted by an explicit extraction pipeline. They must not silently become `[file id]` placeholder text.

Keep the original artifact. Generate thumbnails or model-sized derivatives separately so UI previews and provider limits do not destroy the source image. PNG is the default for screenshots because text remains sharp; derived downscaled images may use another encoding where appropriate.

## Composer UX

### Controls

- Add a universal paperclip button.
- Keep the camera button only when `AttachmentAcquisitionCapabilities.captureScreenshot` or `selectScreenshot` is available.
- Use capability objects supplied by the platform implementation, not platform-name conditionals in `MessageInput`.
- On compact/mobile layouts, keep these actions in the same action row and allow an overflow menu if user-configured instruction buttons make the row crowded.

### Draft attachments

Show attachments above the text field as compact cards:

- image thumbnail or file-type icon;
- display name and compact size;
- upload progress;
- retry state for failed uploads;
- remove action;
- clear distinction between uploading, ready, and failed.

The message may contain attachments with no text. The send button is enabled when text is nonblank or at least one attachment is ready. Sending is blocked while an attachment upload is incomplete unless the user removes it.

Queued messages retain attachment references. Editing, reordering, moving between `AFTER_TOOL_RESULT` and `END_OF_TURN`, and cancelling a queued message must preserve or release those references correctly.

### Drag, drop, and paste

Desktop web and JVM should support:

- dropping one or more files anywhere over the composer/conversation surface;
- a visible drop overlay while compatible data is over the window;
- pasting an image directly from the clipboard;
- pasting files where the platform exposes them;
- preserving ordinary text paste behavior when the clipboard has no supported binary item.

All these paths call the same `stageAttachments()` use case as the picker and screenshot action.

### Cross-client behavior

Draft composer state can remain client-local initially. Once a message is queued or sent, its attachments are Server-owned and render identically on every client. If cross-client draft synchronization is added later, it should synchronize draft artifact IDs rather than raw bytes.

## Client Acquisition Contract

Replace the path-returning screenshot controller with a general client-side acquisition port:

```kotlin
interface AttachmentAcquisitionController {
    val capabilities: StateFlow<AttachmentAcquisitionCapabilities>

    suspend fun pickFiles(options: FilePickerOptions): List<LocalAttachment>
    suspend fun pickImages(options: ImagePickerOptions): List<LocalAttachment>
    suspend fun acquireScreenshot(): LocalAttachment?
}

data class LocalAttachment(
    val displayName: String?,
    val mediaType: MediaType,
    val sizeBytes: Long?,
    val content: AttachmentContentSource,
)
```

`AttachmentContentSource` is platform-specific streaming content. It must not leak a path into domain messages. The presentation layer immediately hands it to the upload use case and then keeps only upload state plus `ArtifactId`.

## Model Screenshot Tool

### Tool contract

Initial public tool:

```text
grz_capture_screenshot
```

It is a Worker-scoped tool. The normal Gromozeka execution target selects the exact Worker. A workspace mount is not required because a display belongs to a Worker, not to a filesystem Workspace.

Implemented input:

```json
{
  "max_long_edge": 2560
}
```

The first slice deliberately means "the complete visible desktop of this exact Worker" and does not guess a window or region. Display/window/region selection and a companion target-listing operation belong to Computer Use, where capture geometry and session identity can be modeled consistently.

Result:

- typed image artifact reference;
- PNG media type and filename metadata, followed by the persisted artifact reference on Server;
- a short textual description only as supplemental data;
- explicit error for unavailable permission, locked session, unsupported compositor, offline Worker, or oversized output.

### Execution behavior

- macOS requires Screen Recording permission for unattended capture;
- Windows uses a native capture API or a reliable OS-backed implementation;
- Linux advertises the capability only when the current desktop/session has a usable capture backend;
- headless Workers do not advertise screen capture;
- no automatic retry on another Worker;
- no focus requirement should be introduced when the OS can capture a window/display without it.

The model sees available screen-capture capability as part of Worker environment/tool discovery. It must always know which Worker produced the screenshot.

## Typed Tool Results

The screenshot tool exposes an existing architectural weakness: `AiToolCallback.call()` currently returns only `String`. The correct foundation is a structured result, for example:

```kotlin
sealed interface AiToolResult {
    data class Content(
        val items: List<AiToolResultItem>,
        val isError: Boolean = false,
    ) : AiToolResult
}

sealed interface AiToolResultItem {
    data class Text(val text: String) : AiToolResultItem
    data class ArtifactRef(val artifactId: ArtifactId, val mediaType: MediaType) : AiToolResultItem
    data class ResourceLink(val uri: String, val mediaType: MediaType?) : AiToolResultItem
}
```

The callback should also be suspendable. Remote Worker execution, MCP calls, and artifact persistence are asynchronous operations; hiding them behind blocking string callbacks makes cancellation and binary results harder.

MCP content blocks should map into this result without flattening images/audio/resources. Provider adapters then map the structured result into each provider's native tool-result format.

## Lifecycle and Limits

Initial limits should be configurable, with conservative defaults rather than hidden provider failures:

- maximum file size;
- maximum attachments per message;
- maximum total bytes per message;
- allowed/blocked media types where required;
- image dimension/decoded-pixel protection;
- upload timeout and idle timeout.

Filenames are display metadata only. Storage keys are generated by Server and never derived as executable paths from user filenames. Downloads use safe content disposition and MIME handling.

Lifecycle:

1. Upload creates `DRAFT` artifact.
2. Accepted message/tool result creates a durable reference and commits it.
3. Removing a draft or cancelling its upload releases it.
4. A periodic low-frequency GC removes unreferenced expired drafts.
5. Deleting a message releases its reference; physical deletion occurs only when no durable reference remains and retention policy allows it.

GC must work from indexed artifact/reference tables, not by scanning/deserializing every conversation.

## Implementation Sequence

### Phase 1: artifact and typed-content foundation

- [x] Add `Artifact`, repository, storage port, local-volume storage implementation, and authenticated upload/download routes.
- [x] Add explicit artifact references to conversation/tool-result content.
- [x] Replace string-only tool results with typed binary results and preserve MCP binary blocks.
- [x] Implement provider materialization for images/files on the currently supported runtimes.
- [x] Add lifecycle and authorization tests.

This is the only large cross-cutting phase. Avoid keeping the old path-in-text behavior as backward compatibility.

### Phase 2: ordinary message attachments

- [x] Add pending attachment state to the composer and queued messages.
- [x] Add file/photo picker implementations.
- [x] Add lazy image previews, aggregate upload state, removal, and attachment-only send.
- [x] Add web/JVM drag-and-drop and web clipboard image/file paste.
- [ ] Add per-file progress/retry and verify sent/queued rendering on two live clients.

### Phase 3: user screenshot action

- [x] Desktop web: `getDisplayMedia()` single-frame capture.
- [x] Native iOS: screenshot-filtered `PhotosPicker`.
- [x] JVM macOS selection and JVM Windows/Linux full-desktop capture.
- [x] Feature-detect and hide the button where no meaningful action exists.
- [x] Remove the old `ScreenCaptureController` and local-path insertion.

### Phase 4: model screenshot tool

- [x] Add Worker capability advertisement and exact Worker routing.
- [x] Add the complete-desktop `grz_capture_screenshot` tool.
- [x] Upload captured bytes to Server as committed tool-result artifacts.
- [x] Render image tool results in UI and send them natively to supported providers.
- [ ] Add capture target inventory and verify denied-permission, locked-session, cancellation, and multi-display behavior as part of Computer Use.

### Phase 5: reuse from Browser Use and Computer Use

- Browser Session screenshots use the same artifact store and typed result.
- Computer Use observations use the same type but retain their Worker and capture-target provenance.
- Do not add a second screenshot storage path inside either subsystem.

## Verification Plan

### Domain and application tests

- artifact state transitions and reference counting;
- message acceptance atomically commits attachment references;
- queue edit/cancel/reorder does not duplicate or orphan references;
- authorization rejects cross-user/project/conversation access;
- duplicate upload completion remains idempotent;
- size and decoded-image limits fail explicitly;
- tool completion cannot reference an unknown or uncommitted artifact.

### Transport and storage tests

- streaming upload/download without loading the whole file into memory;
- cancelled and interrupted uploads leave only reclaimable drafts;
- local-volume restart recovery;
- Worker upload and normal Server event delivery;
- range/download behavior where needed for previews.

### Provider tests

- OpenAI API/subscription image input mapping;
- Anthropic image tool result and user image mapping;
- Claude Code Worker staging and cleanup;
- MCP image content survives as image content rather than placeholder text;
- unsupported file types fail visibly.

### UI/platform tests

- image/file picker, paste, drop, preview, retry, remove, and attachment-only send;
- desktop browser capture waits for a real frame and stops all tracks;
- screenshot control is absent when unsupported;
- iOS screenshot picker filters correctly and needs no broad Photos permission;
- queued attachment appears once on all connected clients;
- large upload progress does not block conversation events.

## Open Product Questions

These do not block the foundation:

- On desktop native clients, should one click open a region selector, a window selector, or a small capture-mode menu?
- Should generic files be exposed to the model immediately, or should non-image files first require explicit extraction/import tools?
- What default per-file and per-message limits feel right for private deployments?
- Should committed conversation artifacts be retained forever with the conversation or use a configurable retention policy?
- Do we want a future explicit client action where the model asks the active client to request a user-approved screenshot?

## Source Notes

- W3C Screen Capture requires user choice on every `getDisplayMedia()` call, transient activation, and non-persistent permission: https://www.w3.org/TR/screen-capture/
- MDN documents the secure-context and browser-compatibility constraints: https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getDisplayMedia
- Apple recommends the system Photos picker for user-selected media without broad photo-library authorization: https://developer.apple.com/documentation/photosui/photospicker
- Apple exposes screenshot filtering and screenshot-specific PhotoKit metadata: https://developer.apple.com/documentation/photokit/phassetcollectionsubtype/phassetcollectionsubtypesmartalbumscreenshots
- Compose Multiplatform supports desktop drag/drop, while platform adapters can supply native picker behavior: https://blog.jetbrains.com/kotlin/2024/10/compose-multiplatform-1-7-0-released/
