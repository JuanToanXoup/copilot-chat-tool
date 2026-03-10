/**
 * bridge.ts — VS Code webview bridge
 *
 * Adapted from the IntelliJ JCEF bridge. This is the ONLY file that changes
 * between IntelliJ and VS Code in the React webview.
 *
 * IntelliJ bridge:
 *   Send:    window.__bridge.postMessage(JSON.stringify({command, ...}))
 *   Receive: CustomEvent('jcef-data') with Base64-decoded {channel, payload}
 *
 * VS Code bridge:
 *   Send:    vscode.postMessage({command, ...})
 *   Receive: window.addEventListener('message') with {channel, payload}
 */

// Acquire the VS Code API (available in webview context)
// @ts-expect-error acquireVsCodeApi is injected by VS Code
const vscode = acquireVsCodeApi();

export interface BridgeData {
  channel: string;
  payload: any;
}

/**
 * Send a command to the extension host.
 *
 * IntelliJ: window.__bridge.postMessage(JSON.stringify(msg))
 * VS Code:  vscode.postMessage(msg)
 */
export function postMessage(msg: Record<string, unknown>): void {
  vscode.postMessage(msg);
}

/**
 * Subscribe to messages from the extension host.
 *
 * IntelliJ: window.addEventListener('jcef-data', handler)
 *   - Data arrives as CustomEvent with Base64-encoded detail
 *   - Decoded via atob → Uint8Array → TextDecoder('utf-8')
 *
 * VS Code: window.addEventListener('message', handler)
 *   - Data arrives as MessageEvent with {channel, payload} directly
 *   - No encoding/decoding needed
 */
export function subscribe(fn: (data: BridgeData) => void): () => void {
  const handler = (event: MessageEvent) => {
    const { channel, payload } = event.data;
    if (channel) {
      fn({ channel, payload });
    }
  };
  window.addEventListener('message', handler);
  return () => window.removeEventListener('message', handler);
}

// === Helper functions (identical API to IntelliJ bridge) ===

export function sendMessage(params: {
  message: string;
  modelId?: string;
  modeId?: string;
  sessionId?: string;
  newSession?: boolean;
  silent?: boolean;
}): void {
  postMessage({ command: 'sendMessage', ...params });
}

export function stopGeneration(sessionId?: string): void {
  postMessage({ command: 'stopGeneration', sessionId });
}

export function requestModels(): void {
  postMessage({ command: 'getModels' });
}

export function requestModes(): void {
  postMessage({ command: 'getModes' });
}

export function requestSessions(): void {
  postMessage({ command: 'getSessions' });
}

export function requestPlaybooks(): void {
  postMessage({ command: 'getPlaybooks' });
}

export function requestSession(sessionId: string): void {
  postMessage({ command: 'getSession', sessionId });
}

export function openFile(filePath: string, line?: number): void {
  postMessage({ command: 'openFile', filePath, line });
}
