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

import React, { useCallback, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { AuthTokenSection, getOrCreateAuthToken } from './authToken';
import { Button } from './tabItem';

type Status =
  | { type: 'connecting'; message: string }
  | { type: 'connected'; message: string }
  | { type: 'error'; message: string }
  | { type: 'error'; versionMismatch: { extensionVersion: string } };

const SUPPORTED_PROTOCOL_VERSION = 2;

const clientName = (() => {
  try {
    return JSON.parse(new URLSearchParams(window.location.search).get('client') ?? '{}').name ?? 'unknown client';
  } catch {
    return 'unknown client';
  }
})();

const ConnectApp: React.FC = () => {
  const [status, setStatus] = useState<Status>({
    type: 'connecting',
    message: `"${clientName}" wants to use this browser profile.`,
  });
  const [approvalRequired, setApprovalRequired] = useState(false);

  const setError = useCallback((message: string) => {
    setApprovalRequired(false);
    setStatus({ type: 'error', message });
  }, []);

  const connect = useCallback(async () => {
    setApprovalRequired(false);
    try {
      const response = await chrome.runtime.sendMessage({ type: 'connect', clientName });
      if (!response?.success) {
        setError(response?.error ?? `"${clientName}" failed to connect.`);
        return;
      }
      setStatus({ type: 'connected', message: `"${clientName}" is connected.` });
      window.setTimeout(() => window.close(), 600);
    } catch (error) {
      setError(`"${clientName}" failed to connect: ${String(error)}`);
    }
  }, [setError]);

  useEffect(() => {
    const run = async () => {
      const params = new URLSearchParams(window.location.search);
      const relayUrl = params.get('mcpRelayUrl');
      if (!relayUrl) {
        setError('The local MCP relay URL is missing.');
        return;
      }

      try {
        const host = new URL(relayUrl).hostname;
        if (host !== '127.0.0.1' && host !== '[::1]') {
          setError(`Only a loopback MCP relay is allowed. Received: ${host}`);
          return;
        }
      } catch {
        setError('The local MCP relay URL is invalid.');
        return;
      }

      const requestedVersion = Number.parseInt(params.get('protocolVersion') ?? '1', 10);
      if (requestedVersion !== SUPPORTED_PROTOCOL_VERSION) {
        if (requestedVersion > SUPPORTED_PROTOCOL_VERSION) {
          setStatus({
            type: 'error',
            versionMismatch: { extensionVersion: chrome.runtime.getManifest().version },
          });
        } else {
          setError('Update Playwright MCP before connecting this bridge.');
        }
        return;
      }

      await chrome.runtime.sendMessage({ type: 'connectionRequested', mcpRelayUrl: relayUrl });
      const token = params.get('token');
      if (token === getOrCreateAuthToken()) {
        await connect();
        return;
      }
      if (token) {
        setError('The Browser Bridge token is invalid.');
        return;
      }
      setApprovalRequired(true);
    };

    void run();
    const keepalive = window.setInterval(() => {
      chrome.runtime.sendMessage({ type: 'keepalive' }).catch(() => {});
    }, 20_000);
    return () => window.clearInterval(keepalive);
  }, [connect, setError]);

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

        <StatusBanner status={status} />

        {approvalRequired && (
          <>
            <div className='permission-card'>
              <strong>Full profile access</strong>
              <p>
                Browser Use will be able to inspect and operate every ordinary tab in this Chrome profile,
                including signed-in websites. Browser-internal and extension pages remain inaccessible.
              </p>
            </div>
            <div className='button-container'>
              <Button variant='default' onClick={() => window.close()}>Cancel</Button>
              <Button variant='primary' onClick={() => void connect()}>Allow Browser Use</Button>
            </div>
            <AuthTokenSection />
          </>
        )}
      </section>
    </main>
  );
};

const StatusBanner: React.FC<{ status: Status }> = ({ status }) => (
  <div className={`status-banner ${status.type}`}>
    {'versionMismatch' in status ? (
      <span>
        Playwright MCP requires a newer bridge than {status.versionMismatch.extensionVersion}. Download the
        matching bridge from your Gromozeka Server.
      </span>
    ) : status.message}
  </div>
);

const container = document.getElementById('root');
if (container)
  createRoot(container).render(<ConnectApp />);
