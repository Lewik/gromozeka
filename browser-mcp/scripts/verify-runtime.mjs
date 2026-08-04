import fs from 'node:fs';
import path from 'node:path';
import { browserMcpCliPath, runtimeRoot, verifyPlaywrightRuntime } from './playwright-patch.mjs';

verifyPlaywrightRuntime();

const packageJson = JSON.parse(
  fs.readFileSync(path.join(runtimeRoot(), 'package.json'), 'utf8'),
);
const lockFile = JSON.parse(
  fs.readFileSync(path.join(runtimeRoot(), 'package-lock.json'), 'utf8'),
);

if (lockFile.packages['node_modules/@playwright/mcp']?.version !== packageJson.dependencies['@playwright/mcp']) {
  throw new Error('Installed Playwright MCP version does not match the pinned package version');
}
if (!fs.statSync(browserMcpCliPath()).isFile()) {
  throw new Error('Playwright MCP CLI was not installed');
}
