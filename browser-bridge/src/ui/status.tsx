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

import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { AuthTokenSection } from './authToken';
import { Button, TabItem } from './tabItem';

type ConnectionStatus = {
  connectedTabIds: number[];
  clientName?: string;
};

const StatusApp: React.FC = () => {
  const [status, setStatus] = useState<ConnectionStatus>({ connectedTabIds: [] });
  const [tabs, setTabs] = useState<chrome.tabs.Tab[]>([]);

  useEffect(() => {
    const load = async () => {
      const nextStatus = await chrome.runtime.sendMessage({ type: 'getConnectionStatus' }) as ConnectionStatus;
      const settledTabs = await Promise.allSettled(
        nextStatus.connectedTabIds.map(tabId => chrome.tabs.get(tabId)),
      );
      setStatus(nextStatus);
      setTabs(settledTabs.flatMap(result => result.status === 'fulfilled' ? [result.value] : []));
    };
    void load();
  }, []);

  const openTab = async (tab: chrome.tabs.Tab) => {
    if (tab.id === undefined) return;
    await chrome.tabs.update(tab.id, { active: true });
    await chrome.windows.update(tab.windowId, { focused: true });
    window.close();
  };

  const disconnect = async () => {
    await chrome.runtime.sendMessage({ type: 'disconnect' });
    window.close();
  };

  return (
    <main className='app-container'>
      <section className='content-wrapper'>
        <header className='brand-header'>
          <img src='icons/icon-48.png' width='48' height='48' alt='' />
          <div>
            <div className='eyebrow'>Gromozeka</div>
            <h1>Browser Bridge</h1>
          </div>
        </header>

        {status.clientName ? (
          <>
            <div className='connection-header'>
              <div>
                <div className='connection-label'>Connected</div>
                <strong>{status.clientName}</strong>
              </div>
              <Button variant='default' onClick={() => void disconnect()}>Disconnect</Button>
            </div>
            <div className='scope-note'>
              All ordinary tabs are available. New tabs join automatically without changing browser groups or focus.
            </div>
            <div className='tab-section-title'>Available now · {tabs.length}</div>
            <div className='tab-list'>
              {tabs.map(tab => (
                <TabItem key={tab.id} tab={tab} onClick={() => void openTab(tab)} />
              ))}
            </div>
          </>
        ) : (
          <div className='status-banner idle'>
            No Browser Use process is connected. Configure one in Gromozeka under Settings &gt; Tools &gt; Browser Use.
          </div>
        )}

        <AuthTokenSection />
      </section>
    </main>
  );
};

const container = document.getElementById('root');
if (container)
  createRoot(container).render(<StatusApp />);
