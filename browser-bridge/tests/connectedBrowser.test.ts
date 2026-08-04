import { describe, expect, test } from 'vitest';
import {
  ConnectedBrowser,
  isNonDebuggableUrl,
  type BrowserBridgeChromeApi,
  type BrowserRelay,
} from '../src/connectedBrowser';

class FakeEvent<T extends (...args: any[]) => void> {
  readonly listeners = new Set<T>();

  addListener(listener: T): void {
    this.listeners.add(listener);
  }

  removeListener(listener: T): void {
    this.listeners.delete(listener);
  }

  emit(...args: Parameters<T>): void {
    for (const listener of this.listeners)
      listener(...args);
  }
}

class FakeRelay implements BrowserRelay {
  readonly attachedTabs = new Set<number>();
  readonly announcedTabs: chrome.tabs.Tab[] = [];
  readonly detachedTabs: number[] = [];
  readonly closeReasons: string[] = [];
  initialized = 0;
  onclose?: () => void;
  ontabattached?: (tabId: number) => void;
  ontabdetached?: (tabId: number) => void;

  attachTab(tab: chrome.tabs.Tab): void {
    this.announcedTabs.push(tab);
  }

  detachTab(tabId: number): void {
    this.detachedTabs.push(tabId);
    this.attachedTabs.delete(tabId);
    this.ontabdetached?.(tabId);
  }

  didInitialize(): void {
    this.initialized++;
  }

  close(reason: string): void {
    this.closeReasons.push(reason);
    this.onclose?.();
  }

  reportAttached(tabId: number): void {
    this.attachedTabs.add(tabId);
    this.ontabattached?.(tabId);
  }
}

function fakeChrome(initialTabs: chrome.tabs.Tab[]) {
  const onCreated = new FakeEvent<(tab: chrome.tabs.Tab) => void>();
  const onUpdated = new FakeEvent<(
    tabId: number,
    changeInfo: chrome.tabs.OnUpdatedInfo,
    tab: chrome.tabs.Tab,
  ) => void>();
  const badges: Array<{ tabId?: number; text: string }> = [];
  const titles: Array<{ tabId?: number; title: string }> = [];
  const colors: Array<{ tabId?: number; color: string | chrome.action.ColorArray }> = [];
  const api: BrowserBridgeChromeApi = {
    tabs: {
      query: async () => initialTabs,
      onCreated,
      onUpdated,
    },
    action: {
      setBadgeText: async details => { badges.push(details); },
      setTitle: async details => { titles.push(details); },
      setBadgeBackgroundColor: async details => { colors.push(details); },
    },
  };
  return { api, onCreated, onUpdated, badges, titles, colors };
}

describe('ConnectedBrowser', () => {
  test('announces every ordinary initial tab before completing the handshake', async () => {
    const relay = new FakeRelay();
    const chrome = fakeChrome([
      { id: 1, url: 'https://example.com' },
      { id: 2, url: 'chrome://settings/' },
      { id: 3, url: 'edge://extensions/' },
      { id: 4, url: 'chrome-extension://example/status.html' },
      { id: 5, url: 'about:blank' },
      { id: 6 },
    ] as chrome.tabs.Tab[]);

    await ConnectedBrowser.create(relay, chrome.api);

    expect(relay.announcedTabs.map(tab => tab.id)).toEqual([1, 5]);
    expect(relay.initialized).toBe(1);
  });

  test('tracks new tabs and URL transitions without browser groups', async () => {
    const relay = new FakeRelay();
    const chrome = fakeChrome([]);
    await ConnectedBrowser.create(relay, chrome.api);

    chrome.onCreated.emit({ id: 7, url: 'https://gromozeka.dev' } as chrome.tabs.Tab);
    chrome.onCreated.emit({ id: 8, url: 'chrome://newtab/' } as chrome.tabs.Tab);
    chrome.onUpdated.emit(
      8,
      { url: 'https://example.org' },
      { id: 8, url: 'https://example.org' } as chrome.tabs.Tab,
    );
    relay.reportAttached(7);
    chrome.onUpdated.emit(
      7,
      { url: 'chrome://version/' },
      { id: 7, url: 'chrome://version/' } as chrome.tabs.Tab,
    );

    expect(relay.announcedTabs.map(tab => tab.id)).toEqual([7, 8]);
    expect(relay.detachedTabs).toEqual([7]);
  });

  test('reports attached tabs in stable order and updates badges', async () => {
    const relay = new FakeRelay();
    const chrome = fakeChrome([]);
    const browser = await ConnectedBrowser.create(relay, chrome.api);

    relay.reportAttached(12);
    relay.reportAttached(3);
    await Promise.resolve();

    expect(browser.connectedTabIds()).toEqual([3, 12]);
    expect(chrome.badges).toContainEqual({ tabId: 12, text: 'G' });
    expect(chrome.titles).toContainEqual({ tabId: 3, title: 'Available to Gromozeka Browser Use' });
  });

  test('removes browser listeners when the relay closes', async () => {
    const relay = new FakeRelay();
    const chrome = fakeChrome([]);
    await ConnectedBrowser.create(relay, chrome.api);

    expect(chrome.onCreated.listeners.size).toBe(1);
    expect(chrome.onUpdated.listeners.size).toBe(1);

    relay.onclose?.();

    expect(chrome.onCreated.listeners.size).toBe(0);
    expect(chrome.onUpdated.listeners.size).toBe(0);
  });
});

describe('isNonDebuggableUrl', () => {
  test.each([
    'chrome://settings/',
    'edge://extensions/',
    'devtools://devtools/bundled/inspector.html',
    'chrome-extension://extension/page.html',
    'edge-extension://extension/page.html',
  ])('rejects browser-internal URL %s', url => {
    expect(isNonDebuggableUrl(url)).toBe(true);
  });

  test.each([
    'https://example.com',
    'http://localhost:8765',
    'file:///tmp/report.html',
    'about:blank',
  ])('accepts ordinary URL %s', url => {
    expect(isNonDebuggableUrl(url)).toBe(false);
  });
});
