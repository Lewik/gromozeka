# Browser Use

> Temporary design note. Delete after Browser Use is implemented and durable contracts are documented elsewhere.

## Goal

Let a model work in the same real browser identity as the user:

- existing cookies, logins, extensions, client certificates, and site state remain available;
- the model can continue a task the user started, and the user can inspect or take over the same tab;
- browser work should avoid stealing keyboard/mouse focus when the browser and website permit it;
- screenshots, downloads, and uploaded files use Gromozeka's common Artifact pipeline;
- all browser execution remains explicitly routed to one Worker.

"No focus" is not the domain requirement. The requirement is: use the user's actual session, preserve normal website behavior, and avoid disturbing the user whenever possible. Some sites or browser operations may require a visible or focused page; that must be an explicit state transition rather than a hidden guarantee we cannot keep.

## Distinction From Other Capabilities

Browser Use is DOM/browser-protocol automation. It is not:

- a generic web-search MCP;
- a Server-side HTTP fetcher;
- desktop Computer Use driven by pixels and OS input;
- the ordinary client's manual screenshot button;
- a headless disposable browser that lacks the user's authenticated state.

If a task can be completed through browser APIs and DOM/accessibility state, Browser Use is preferable to Computer Use because it can normally operate without moving the user's pointer or typing into the foreground application.

## Execution Topology

A browser belongs to a machine and user profile, therefore Browser Use is a Worker-scoped capability.

```text
Conversation runtime on Server
        |
        | exact Worker + BrowserSessionId
        v
Worker Browser Controller
        |
        | extension/CDP bridge
        v
User's browser profile and tabs
```

Server owns orchestration and conversation state. Worker owns the local connection to the browser. The client never sends browser commands directly to Worker.

No automatic rerouting occurs when Worker is offline. A browser session on another Worker is a different resource even if it belongs to the same user.

## Connection Modes

### 1. Extension bridge in the user's normal profile

This is the preferred mode for the stated product goal.

- The user installs a Gromozeka browser extension into the profile they already use.
- The extension connects locally to the Worker or to a Worker-owned native messaging host.
- It exposes explicitly attached tabs through browser debugging/extension APIs.
- Cookies and authentication remain browser-owned; Gromozeka does not copy the cookie database.
- The extension can show which tabs are attached and allow detaching immediately.

This follows the same broad direction as current Codex and Claude browser integrations: an extension is the bridge into a regular profile. It also avoids depending on remote-debugging access to Chrome's default data directory, which Chrome intentionally restricts.

### 2. Gromozeka-managed persistent browser profile

Useful as a fallback and for automation-only environments:

- Worker launches Chrome/Chromium with a dedicated persistent profile directory;
- user signs in once inside that profile;
- subsequent Browser Sessions reuse the profile;
- automation can create background tabs and windows more predictably.

This is not the same session as the user's ordinary Chrome profile, so it does not satisfy the primary handoff goal by itself.

### 3. Disposable/headless browser

Useful for tests, scraping, and unauthenticated tasks, but not a replacement for either persistent mode. It should be an explicit profile kind rather than a silent fallback.

## Domain Model

```kotlin
data class BrowserProfile(
    val id: BrowserProfileId,
    val workerId: WorkerId,
    val name: String,
    val kind: BrowserProfileKind,
    val enabled: Boolean,
)

enum class BrowserProfileKind {
    EXTENSION_ATTACHED,
    MANAGED_PERSISTENT,
    DISPOSABLE,
}

data class BrowserSession(
    val id: BrowserSessionId,
    val conversationId: Conversation.Id,
    val workerId: WorkerId,
    val profileId: BrowserProfileId,
    val state: BrowserSessionState,
    val activePageId: BrowserPageId?,
    val createdAt: Instant,
    val lastActivityAt: Instant,
)
```

`BrowserSession` is a logical Server record for a conversation's use of a local browser connection. Browser process IDs, CDP target IDs, and extension ports are Worker-local details.

Important invariants:

- `workerId` never changes during a session;
- a page ID is meaningful only inside its Browser Session;
- reconnect may rebind a known tab only after Worker verifies identity and state;
- loss of connection moves the session to `UNKNOWN` or `DISCONNECTED`, never silently to another browser;
- actions with unknown outcome are never automatically retried.

## Tab Attachment and Handoff

The user should be able to:

1. Open and authenticate a site normally.
2. Attach the current tab to Gromozeka from the extension or Gromozeka UI.
3. Ask the model to continue.
4. Observe the tab without being forced to keep it foregrounded.
5. Take control by interacting with it.
6. Return control to the model without creating a new session.

The first implementation should distinguish:

- `AVAILABLE`: tab belongs to the profile but is not controlled;
- `ATTACHED`: model may inspect and act;
- `USER_ACTIVE`: user recently interacted; model pauses mutations until control is returned;
- `MODEL_ACTIVE`: model may mutate the page;
- `DETACHED` or `CLOSED`.

User activity detection can begin conservatively with extension events and an explicit Take control/Continue button. It should not guess based solely on browser focus because the user may inspect without intending to stop the model.

## Focus and Background Behavior

DOM/CDP operations usually do not require OS-level focus. Navigation, DOM clicks, form filling, JavaScript evaluation, accessibility snapshots, and screenshots can generally run in a background tab.

However, there are real exceptions:

- pages may throttle timers or pause work in background tabs;
- visibility APIs let sites behave differently when hidden;
- native file pickers, WebAuthn, client certificates, payment UI, CAPTCHA, and some media flows require user interaction;
- a site may intentionally require a visible/focused page;
- the browser may bring security prompts to the foreground.

Therefore:

- default actions are background-safe protocol operations;
- `bringToFront` is a separate explicit operation;
- the runtime reports `USER_INTERACTION_REQUIRED` rather than looping;
- the model explains what needs attention and waits for the user;
- no fake focus/visibility override is a correctness guarantee. Provider-specific emulation may be an opt-in workaround, not the default contract.

## Tool Surface

Avoid exposing every low-level CDP method directly. Start with a compact stable browser vocabulary:

- `grz_browser_profiles`
- `grz_browser_sessions`
- `grz_browser_open_session`
- `grz_browser_close_session`
- `grz_browser_pages`
- `grz_browser_attach_page`
- `grz_browser_open_page`
- `grz_browser_snapshot`
- `grz_browser_screenshot`
- `grz_browser_navigate`
- `grz_browser_click`
- `grz_browser_type`
- `grz_browser_select`
- `grz_browser_scroll`
- `grz_browser_wait`
- `grz_browser_evaluate`
- `grz_browser_back`
- `grz_browser_forward`
- `grz_browser_reload`

All action tools require `browserSessionId` and usually `pageId`. The exact Worker follows from the session and must be validated against the supplied execution target.

The model should primarily receive accessibility/semantic snapshots with stable element references. Screenshots complement semantic state; they should not be the only way to click normal web controls.

Downloads become Artifacts. Uploading a conversation Artifact to a page stages it on the same Worker and uses the browser's upload protocol without opening a foreground native file dialog where possible.

## Results and Observability

Every action result includes:

- Browser Session and page identity;
- final URL and title where meaningful;
- whether navigation or DOM changed;
- concise semantic observations;
- artifact IDs for screenshots/downloads;
- explicit state when user interaction is required;
- enough error detail for the model to decide the next action.

Runtime UI should show:

- Worker and profile;
- attached tabs with favicon/title/URL;
- who currently controls the tab;
- latest action and status;
- pause/detach/close controls;
- a button to bring the page forward.

The full action stream remains available in tool calls, but Runtime shows only a concise process summary.

## Session Recovery

Extension or Worker restarts must not imply that a browser action is safe to repeat.

On reconnect:

1. Worker lists attached/known tabs and their current URLs.
2. Server reconciles Browser Session records.
3. Exact matches return to `ATTACHED` but no interrupted mutation is replayed.
4. Missing or ambiguous tabs become `UNKNOWN`/`DETACHED`.
5. The model receives the reconciliation result on its next safe input.

No automatic retry of clicks, form submissions, navigation, payments, or downloads.

## Browser Extension Boundary

The extension should remain a thin local bridge:

- attach/detach tabs;
- collect semantic page state;
- execute a bounded command protocol;
- stream events/results to its Worker;
- show visible attachment/control state.

It should not own conversations, prompts, model access, or Server credentials. It authenticates only to the local Worker bridge using a per-install secret or native messaging channel.

Extension permissions should be explicit and explainable. Host permissions may be broad because the product intentionally supports arbitrary authenticated sites, but the extension must not hide that scope.

## Security Position

Gromozeka does not add per-click approvals or site denylists. Browser Use runs with the authority of the selected browser profile, just as Worker tools run with the authority of the Worker account.

Normal system security still applies:

- authenticated and encrypted Server/Worker channels;
- exact Worker routing;
- extension-to-Worker local authentication;
- artifact authorization;
- no raw cookie/database export;
- visible session/control state;
- normal browser and OS credential boundaries.

## Implementation Sequence

1. Finish common Artifact support and typed image/file tool results.
2. Prototype an extension bridge that attaches one existing Chrome tab on one Worker.
3. Implement Browser Session and semantic snapshot/screenshot.
4. Add navigate/click/type/wait with deterministic action IDs and no retries.
5. Add user/model handoff and Runtime UI.
6. Add downloads/uploads through Artifacts.
7. Add managed persistent and disposable profiles.
8. Add Firefox/Safari only if their extension/debugging APIs support the same product contract without a separate architecture.

## Verification

- attach a logged-in tab without copying cookies;
- continue an operation started manually;
- user takes control, edits the page, and returns it;
- background interaction does not move the OS pointer or steal keyboard focus in ordinary cases;
- user-interaction-required flows stop and explain themselves;
- reconnect never repeats a possibly completed mutation;
- two Browser Sessions on different Workers never cross-route;
- screenshots/downloads appear once as authorized Artifacts;
- extension disconnection is visible in Runtime and to the model.

## Open Questions

- Is explicit per-tab attachment enough, or should the user be able to attach an entire browser window/profile?
- How aggressively should recent user interaction pause model mutations?
- Should a Browser Session survive conversation completion by default?
- Which actions require an automatic before/after screenshot for debugging?
- Should managed profiles be configured per User, Project, or Worker?

## Source Notes

- Codex Browser uses a separate profile, while its Chrome extension integrates with a regular profile: https://learn.chatgpt.com/docs/browser and https://learn.chatgpt.com/docs/chrome-extension
- Claude Code's Chrome integration uses a browser extension and works with the user's browser session: https://code.claude.com/docs/en/chrome
- Playwright MCP supports an extension connection to existing browser tabs: https://playwright.dev/mcp/configuration/browser-extension
- Chrome restricts remote debugging of the default profile: https://developer.chrome.com/blog/remote-debugging-port
- Chrome exposes extension window creation without initial focus: https://developer.chrome.com/docs/extensions/reference/api/windows
- Background pages can be throttled or frozen: https://developer.chrome.com/blog/background_tabs and https://developer.chrome.com/docs/web-platform/page-lifecycle-api
