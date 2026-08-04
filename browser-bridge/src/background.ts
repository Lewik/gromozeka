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

import { debugLog } from './relayConnection';
import { PendingConnections } from './pendingConnection';
import { ConnectedBrowser } from './connectedBrowser';

type PageMessage = {
  type: 'connectionRequested';
  mcpRelayUrl: string;
} | {
  type: 'connect';
  clientName?: string;
} | {
  type: 'getConnectionStatus';
} | {
  type: 'disconnect';
} | {
  type: 'keepalive';
};

class GromozekaBrowserBridge {
  private _activeBrowser: ConnectedBrowser | undefined;
  private _activeClientName: string | undefined;
  private _pendingConnections = new PendingConnections();

  constructor() {
    chrome.runtime.onMessage.addListener(this._onMessage.bind(this));
    chrome.action.onClicked.addListener(this._onActionClicked.bind(this));
  }

  // Promise-based message handling is not supported in Chrome: https://issues.chromium.org/issues/40753031
  private _onMessage(message: PageMessage, sender: chrome.runtime.MessageSender, sendResponse: (response: any) => void) {
    switch (message.type) {
      case 'connectionRequested':
        this._pendingConnections.create(sender.tab!.id!, message.mcpRelayUrl);
        sendResponse({ success: true });
        return false;
      case 'connect': {
        this._connect(sender.tab!.id!, message.clientName).then(
            () => sendResponse({ success: true }),
            (error: any) => sendResponse({ success: false, error: error.message }));
        return true; // Return true to indicate that the response will be sent asynchronously
      }
      case 'getConnectionStatus':
        sendResponse({
          connectedTabIds: this._activeBrowser?.connectedTabIds() ?? [],
          clientName: this._activeClientName,
        });
        return false;
      case 'disconnect':
        try {
          this._disconnect('User disconnected');
          sendResponse({ success: true });
        } catch (error: any) {
          sendResponse({ success: false, error: error.message });
        }
        return true;
      case 'keepalive':
        // Connect page pings us every ~20s so receiving this message resets
        // the MV3 service worker idle timer and keeps the relay WebSocket alive.
        return false;
    }
  }

  private async _connect(selectorTabId: number, clientName: string | undefined): Promise<void> {
    try {
      this._disconnect('Another connection is requested');

      const connection = await this._pendingConnections.take(selectorTabId);
      if (!connection)
        throw new Error('Pending client connection closed');

      const browser = await ConnectedBrowser.create(connection);
      browser.onclose = () => {
        if (this._activeBrowser === browser) {
          this._activeBrowser = undefined;
          this._activeClientName = undefined;
        }
      };
      this._activeBrowser = browser;
      this._activeClientName = clientName;
    } catch (error: any) {
      debugLog(`Failed to connect browser from tab ${selectorTabId}:`, error.message);
      throw error;
    }
  }

  private async _onActionClicked(): Promise<void> {
    await chrome.tabs.create({
      url: chrome.runtime.getURL('status.html'),
      active: true
    });
  }

  private _disconnect(reason: string) {
    this._activeBrowser?.close(reason);
    this._activeBrowser = undefined;
    this._activeClientName = undefined;
  }
}

new GromozekaBrowserBridge();
