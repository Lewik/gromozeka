import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

export const upstreamExtensionId = 'mmlmfjhmonkocbjadbfplnigmagldckm';
export const extensionIdEnvironment = 'PLAYWRIGHT_MCP_EXTENSION_ID';

const require = createRequire(import.meta.url);
const packageRoot = path.dirname(require.resolve('playwright-core/package.json'));

export const patchTargets = [
  {
    path: path.join(packageRoot, 'lib/tools/utils/extension.js'),
    replacements: [
      {
        original: `const playwrightExtensionId = "${upstreamExtensionId}";`,
        patched: `const playwrightExtensionId = process.env.${extensionIdEnvironment} || "${upstreamExtensionId}";`,
      },
    ],
  },
  {
    path: path.join(packageRoot, 'lib/coreBundle.js'),
    replacements: [
      {
        original: `playwrightExtensionId = "${upstreamExtensionId}";`,
        patched: `playwrightExtensionId = process.env.${extensionIdEnvironment} || "${upstreamExtensionId}";`,
      },
      {
        original: 'return await playwright.chromium.connectOverCDP(relay.cdpEndpoint(), { isLocal: true, timeout: 0 });',
        patched: 'return await playwright.chromium.connectOverCDP(relay.cdpEndpoint(), { isLocal: true, noDefaults: true, timeout: 0 });',
      },
    ],
  },
];

export function patchPlaywrightRuntime() {
  for (const target of patchTargets) {
    let source = fs.readFileSync(target.path, 'utf8');
    for (const replacement of target.replacements) {
      const originalCount = countOccurrences(source, replacement.original);
      const patchedCount = countOccurrences(source, replacement.patched);
      if (originalCount === 1 && patchedCount === 0) {
        source = source.replace(replacement.original, replacement.patched);
      } else if (originalCount !== 0 || patchedCount !== 1) {
        throw new Error(
          `Unexpected Playwright source in ${target.path}: expected one original or one patched occurrence`,
        );
      }
    }
    fs.writeFileSync(target.path, source);
  }
}

export function verifyPlaywrightRuntime() {
  for (const target of patchTargets) {
    const source = fs.readFileSync(target.path, 'utf8');
    for (const replacement of target.replacements) {
      if (countOccurrences(source, replacement.original) !== 0) {
        throw new Error(`Unpatched Playwright source remains in ${target.path}`);
      }
      if (countOccurrences(source, replacement.patched) !== 1) {
        throw new Error(`Expected Playwright patch is missing from ${target.path}`);
      }
    }
  }
}

export function browserMcpCliPath() {
  return path.join(
    path.dirname(require.resolve('@playwright/mcp/package.json')),
    'cli.js',
  );
}

export function runtimeRoot() {
  return path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
}

function countOccurrences(source, value) {
  return source.split(value).length - 1;
}
