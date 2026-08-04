/**
 * Copyright (c) Microsoft Corporation.
 * Modified by the Gromozeka project in 2026.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const NON_DEBUGGABLE_SCHEMES = [
  'chrome:',
  'chrome-extension:',
  'devtools:',
  'edge:',
  'edge-extension:',
];

const CONNECTED_BADGE = {
  text: 'G',
  color: '#137BFF',
  title: 'Available to Gromozeka Browser Use',
};

type EventSource<T extends (...args: any[]) => void> = {
  addListener(listener: T): void;
  removeListener(listener: T): void;
};

export type BrowserBridgeChromeApi = {
  tabs: {
    query(queryInfo: chrome.tabs.QueryInfo): Promise<chrome.tabs.Tab[]>;
    onCreated: EventSource<(tab: chrome.tabs.Tab) => void>;
    onUpdated: EventSource<(
      tabId: number,
      changeInfo: chrome.tabs.OnUpdatedInfo,
      tab: chrome.tabs.Tab,
    ) => void>;
  };
  action: {
    setBadgeText(details: chrome.action.BadgeTextDetails): Promise<void>;
    setBadgeBackgroundColor(details: chrome.action.BadgeColorDetails): Promise<void>;
    setTitle(details: chrome.action.TitleDetails): Promise<void>;
  };
};

export type BrowserRelay = {
  readonly attachedTabs: ReadonlySet<number>;
  onclose?: () => void;
  ontabattached?: (tabId: number) => void;
  ontabdetached?: (tabId: number) => void;
  attachTab(tab: chrome.tabs.Tab): void;
  detachTab(tabId: number): void;
  didInitialize(): void;
  close(reason: string): void;
};

export function isNonDebuggableUrl(url: string | undefined): boolean {
  return !!url && NON_DEBUGGABLE_SCHEMES.some(scheme => url.startsWith(scheme));
}

export class ConnectedBrowser {
  private readonly _connection: BrowserRelay;
  private readonly _chrome: BrowserBridgeChromeApi;
  private readonly _onTabCreatedListener: (tab: chrome.tabs.Tab) => void;
  private readonly _onTabUpdatedListener: (
    tabId: number,
    changeInfo: chrome.tabs.OnUpdatedInfo,
    tab: chrome.tabs.Tab,
  ) => void;
  private _closed = false;

  onclose?: () => void;

  static async create(
    connection: BrowserRelay,
    chromeApi: BrowserBridgeChromeApi = chrome,
  ): Promise<ConnectedBrowser> {
    const browser = new ConnectedBrowser(connection, chromeApi);
    try {
      await browser._initialize();
      if (browser._closed)
        throw new Error('Browser relay closed during initialization');
      return browser;
    } catch (error) {
      browser.close(`Browser initialization failed: ${String(error)}`);
      throw error;
    }
  }

  private constructor(connection: BrowserRelay, chromeApi: BrowserBridgeChromeApi) {
    this._connection = connection;
    this._chrome = chromeApi;
    this._onTabCreatedListener = tab => this._attachIfAvailable(tab);
    this._onTabUpdatedListener = (tabId, changeInfo, tab) => {
      this._onTabUpdated(tabId, changeInfo, tab);
    };

    this._connection.onclose = () => this._onConnectionClose();
    this._connection.ontabattached = tabId => void this._updateBadge(tabId, CONNECTED_BADGE);
    this._connection.ontabdetached = tabId => void this._updateBadge(tabId, { text: '' });
    this._chrome.tabs.onCreated.addListener(this._onTabCreatedListener);
    this._chrome.tabs.onUpdated.addListener(this._onTabUpdatedListener);
  }

  connectedTabIds(): number[] {
    return [...this._connection.attachedTabs].sort((left, right) => left - right);
  }

  close(reason: string): void {
    if (this._closed) return;
    this._connection.close(reason);
    this._onConnectionClose();
  }

  private async _initialize(): Promise<void> {
    const tabs = await this._chrome.tabs.query({});
    for (const tab of tabs)
      this._attachIfAvailable(tab);

    // Playwright waits for this boundary before attaching debuggers to the
    // complete initial tab snapshot.
    this._connection.didInitialize();
  }

  private _attachIfAvailable(tab: chrome.tabs.Tab): void {
    const url = tab.pendingUrl ?? tab.url;
    if (tab.id === undefined || url === undefined || isNonDebuggableUrl(url)) return;
    this._connection.attachTab(tab);
  }

  private _onTabUpdated(
    tabId: number,
    changeInfo: chrome.tabs.OnUpdatedInfo,
    tab: chrome.tabs.Tab,
  ): void {
    if (changeInfo.url === undefined) return;

    if (isNonDebuggableUrl(changeInfo.url)) {
      if (this._connection.attachedTabs.has(tabId))
        this._connection.detachTab(tabId);
      return;
    }

    if (this._connection.attachedTabs.has(tabId))
      void this._updateBadge(tabId, CONNECTED_BADGE);
    else
      this._connection.attachTab(tab);
  }

  private _onConnectionClose(): void {
    if (this._closed) return;
    this._closed = true;
    this._chrome.tabs.onCreated.removeListener(this._onTabCreatedListener);
    this._chrome.tabs.onUpdated.removeListener(this._onTabUpdatedListener);
    this.onclose?.();
  }

  private async _updateBadge(
    tabId: number,
    badge: { text: string; color?: string; title?: string },
  ): Promise<void> {
    try {
      await Promise.all([
        this._chrome.action.setBadgeText({ tabId, text: badge.text }),
        this._chrome.action.setTitle({ tabId, title: badge.title ?? '' }),
        badge.color
          ? this._chrome.action.setBadgeBackgroundColor({ tabId, color: badge.color })
          : Promise.resolve(),
      ]);
    } catch {
      // The tab may disappear while asynchronous badge updates are in flight.
    }
  }
}
