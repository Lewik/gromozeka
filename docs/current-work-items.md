# Current work items

## MCP configuration ownership

Status: implemented and verified end to end.

- The Server database is the source of truth for external MCP definitions and
  accepted tool snapshots.
- Every external MCP server is assigned to one exact Worker. That Worker owns
  the live connection, handshake, tool discovery, and execution.
- Control MCP tools create, update, refresh, list, inspect, and delete external
  MCP servers through session-addressed Worker control messages.
- `tools/list_changed` marks a stored definition as refreshable; accepting a
  changed tool surface always requires an explicit refresh.
- Tool capability summaries are generated per source fingerprint and cached in
  PostgreSQL. They are regenerated when an explicit MCP mutation accepts a new
  fingerprint.
- Secret references and system-wide authentication remain separate future
  security work. Raw configuration values currently follow the same trusted
  operator boundary as the rest of the development runtime.

## UI typography and themes

Status: implemented and verified in the web client.

- Conversation Markdown now uses the application's compact Material typography
  scale instead of the renderer's oversized defaults.
- Material inverse, outline, and scrim colors now follow the active theme rather
  than dark-only hardcoded fallbacks.
- Dark and Light built-in themes were verified in the web client.

## Web startup background

Status: implemented; awaiting manual verification.

- Remove the white frame between the HTML loader and the first Compose frame.
- Keep the initial document/canvas background consistent with the selected or
  best-known theme without introducing stale asset caching.

## Glyph coverage and font licensing

Status: implemented and verified on JVM and web builds.

- UI-owned emoji markers were replaced with Material vector icons or plain
  localized labels so their rendering no longer depends on color-emoji fonts.
- Arbitrary message text continues to use platform font fallback; Compose web
  loads script-specific Noto fallback fonts on demand.
- No font was bundled, avoiding unnecessary binary growth and licensing notices.

## Memory progress accuracy

Status: implemented; awaiting manual verification.

- Compare the memory progress shown in the runtime panel with persisted memory
  run state and runtime events.
- Verify whether the UI jumps to a near-complete state before the underlying
  work reaches that stage.
- Keep the UI declarative: derive the visible stage and progress from one
  authoritative state model instead of mutating labels or percentages in
  response to isolated events.

## Conversation scroll stability

Status: implemented and verified in the web client; awaiting user verification.

- Reproduce the regression where gradual upward scrolling suddenly jumps to the
  beginning of the thread.
- Check whether message expansion changes item heights or whether auto-scroll
  and scroll anchoring move the viewport.
- Preserve the user's explicit scroll position while older messages enter the
  viewport.

## Windows launchers

Status: complete.

- Replaced the plain Gradle application distributions for Server and Worker
  with Spring Boot distributions.
- The generated Windows launchers now use `java -jar` instead of expanding the
  full dependency classpath beyond the `cmd.exe` command-line limit.
- Boot distributions and the full project build pass.

## Working order

1. Verify the startup background, memory progress, and conversation scrolling.
