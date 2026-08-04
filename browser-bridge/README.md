# Gromozeka Browser Bridge

Gromozeka Browser Bridge connects Playwright MCP to every ordinary tab in an
existing Chrome, Edge, or Chromium profile. It preserves the profile's cookies,
logins, extensions, client certificates, and site state without copying them to
Gromozeka.

Browser-internal pages such as `chrome://`, `edge://`, DevTools, and extension
pages cannot be controlled. Browser Use may create, close, navigate, inspect,
and interact with every other tab while the bridge is connected.

## Install

1. Download `gromozeka-browser-bridge.zip` from the Gromozeka release matching
   your Server and extract it into a permanent directory.
2. Remove the official Playwright Extension. The two extensions use the same
   compatibility ID and cannot be installed together.
3. Open `chrome://extensions` or `edge://extensions`.
4. Enable **Developer mode**.
5. Select **Load unpacked** and choose the extracted directory containing
   `manifest.json`.
6. Open the bridge from the browser toolbar and copy its
   `PLAYWRIGHT_MCP_EXTENSION_TOKEN` value.
7. In Gromozeka, open **Settings > Tools > Browser Use**, select the Worker on
   this machine, and paste the token into **Extension token**.

The token is optional. Without it, the bridge asks for approval whenever a new
Playwright MCP process connects.

## Compatibility

The bridge intentionally retains the public manifest key and extension ID used
by Playwright Extension. Playwright MCP currently opens a fixed
`chrome-extension://mmlmfjhmonkocbjadbfplnigmagldckm/connect.html` URL, so this
keeps the wire protocol compatible without distributing a modified MCP server.

## Build

Requires Node.js 22.12 or newer.

```bash
npm ci
npm test
npm run build
```

Load `dist/` as the unpacked extension.

## License

This directory is licensed under Apache License 2.0. It contains modified
portions of Playwright's browser extension. See `LICENSE`, `NOTICE`, and
`THIRD_PARTY_NOTICES.txt`, and `UPSTREAM.md`.
