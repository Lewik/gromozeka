import { describe, expect, test } from 'vitest';
import { browserBridgeCommandArguments } from '../src/relayConnection';

describe('browserBridgeCommandArguments', () => {
  test('creates Playwright tabs in the background', () => {
    expect(browserBridgeCommandArguments('chrome.tabs.create', [{ url: 'https://example.com' }]))
        .toEqual([{ url: 'https://example.com', active: false }]);
  });

  test('does not let the relay override background tab creation', () => {
    expect(browserBridgeCommandArguments('chrome.tabs.create', [{ active: true }]))
        .toEqual([{ active: false }]);
  });

  test('preserves arguments for other allowed commands', () => {
    const args = [{ tabId: 7 }];
    expect(browserBridgeCommandArguments('chrome.tabs.remove', args)).toBe(args);
  });
});
