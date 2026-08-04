# Gromozeka Browser MCP Runtime

This private package pins Playwright MCP and applies the small compatibility
patches required by Gromozeka Browser Bridge. Release packaging installs it
once and includes the resulting runtime in every standalone Worker archive.

The patch keeps the official extension ID as a fallback, selects Gromozeka's
extension through `PLAYWRIGHT_MCP_EXTENSION_ID`, and preserves the existing
browser context without Playwright's default focus or color-scheme overrides.
