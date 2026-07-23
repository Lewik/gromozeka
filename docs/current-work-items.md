# Current work items

## MCP configuration ownership

Status: design pending.

- Document the current split between Gromozeka's server-side MCP endpoint and
  Worker-side external MCP clients.
- Decide how external MCP definitions, Worker assignment, and secrets should be
  managed centrally.
- Do not migrate the configuration until the server/Worker contract has been
  agreed explicitly.

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

Status: pending.

- Identify characters that currently render as missing glyphs.
- Audit the font fallback behavior on JVM, browser, and mobile surfaces.
- If a bundled font is needed, use a license suitable for commercial and
  corporate distribution and retain the required notices.

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
2. Glyph coverage and font licensing.
3. MCP configuration migration after its architecture is agreed.
