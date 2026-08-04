# Upstream

The bundled runtime uses `@playwright/mcp` version `0.0.78` and its pinned
Playwright dependencies. Release builds apply the deterministic patch in
`scripts/playwright-patch.mjs`; they fail if the expected upstream source has
changed.

Upstream repositories:

- https://github.com/microsoft/playwright-mcp
- https://github.com/microsoft/playwright
