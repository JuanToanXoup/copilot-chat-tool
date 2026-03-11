/**
 * Bridge between the React UI and the Kotlin plugin.
 *
 * Kotlin injects window.__bridge at page load via JBCefJSQuery.
 * - postMessage(json): sends a JSON string to Kotlin
 *
 * Kotlin pushes data via CustomEvent('jcef-data') with:
 *   e.detail = { channel: string, payload: any }
 *
 * This module wraps that raw API with typed helpers.
 */

declare global {
  interface Window {
    __bridge?: {
      postMessage: (json: string) => void
    }
  }
}

export interface JcefDataEvent {
  channel: string
  payload: any
}

/** Send a command to the Kotlin side */
export function postMessage(msg: Record<string, unknown>): void {
  if (window.__bridge) {
    window.__bridge.postMessage(JSON.stringify(msg))
  } else {
    console.warn('[bridge] not ready, message dropped:', msg)
  }
}

/**
 * Subscribe to data pushes from Kotlin.
 * Kotlin calls panel.pushData(channel, json) which dispatches CustomEvent('jcef-data').
 * Returns an unsubscribe function.
 */
export function subscribe(fn: (data: JcefDataEvent) => void): () => void {
  const handler = (e: Event) => {
    const detail = (e as CustomEvent).detail as JcefDataEvent
    fn(detail)
  }

  // If bridge is already ready, subscribe immediately
  window.addEventListener('jcef-data', handler)

  return () => {
    window.removeEventListener('jcef-data', handler)
  }
}

/** Request the list of available models */
export function requestModels(): void {
  postMessage({ command: 'getModels' })
}

/** Request the list of available modes */
export function requestModes(): void {
  postMessage({ command: 'getModes' })
}

/** Stop the current generation */
export function stopGeneration(): void {
  postMessage({ command: 'stopGeneration' })
}

/** Request stored sessions */
export function requestSessions(): void {
  postMessage({ command: 'getSessions' })
}

/** Request stored playbooks */
export function requestPlaybooks(): void {
  postMessage({ command: 'getPlaybooks' })
}

/** Request a single session by ID for replay */
export function requestSession(sessionId: string): void {
  postMessage({ command: 'getSession', sessionId })
}

/** Send a chat message */
export function sendMessage(opts: {
  message: string
  modelId?: string
  modeId?: string
  sessionId?: string
  newSession?: boolean
  silent?: boolean
}): void {
  postMessage({ command: 'sendMessage', ...opts })
}
