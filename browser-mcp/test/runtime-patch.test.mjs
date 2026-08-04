import fs from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  extensionIdEnvironment,
  patchPlaywrightRuntime,
  patchTargets,
  upstreamExtensionId,
  verifyPlaywrightRuntime,
} from '../scripts/playwright-patch.mjs';

test('Playwright runtime uses the configured extension without changing browser defaults', () => {
  patchPlaywrightRuntime();
  patchPlaywrightRuntime();
  verifyPlaywrightRuntime();

  const runtimeSource = patchTargets.map(target => fs.readFileSync(target.path, 'utf8')).join('\n');
  assert.match(runtimeSource, new RegExp(`process\\.env\\.${extensionIdEnvironment}`));
  assert.match(runtimeSource, /noDefaults: true/);
  assert.match(runtimeSource, /_tabSessionPromises/);
  assert.match(runtimeSource, /_attachTabOnce/);
  assert.match(runtimeSource, new RegExp(upstreamExtensionId));
});
