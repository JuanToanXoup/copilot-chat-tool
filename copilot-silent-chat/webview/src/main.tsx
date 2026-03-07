import { StrictMode, useState, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import ArchitectureView from './architecture/ArchitectureView.tsx'
import PlaybookDagView from './playbook/PlaybookDagView.tsx'

/**
 * Captures set-mode at the module level so it is never missed,
 * regardless of React mount timing. The listener is registered
 * synchronously when the script loads — before React mounts.
 */
// In Vite dev mode, ?mode=app works (real HTTP server). In JCEF, query params
// don't work on the custom scheme, so Kotlin pushes set-mode via the bridge.
let capturedMode: string | null = new URLSearchParams(window.location.search).get('mode')
const modeListeners = new Set<(mode: string) => void>()

window.addEventListener('jcef-data', (e: Event) => {
  const detail = (e as CustomEvent).detail
  if (detail?.channel === 'set-mode') {
    capturedMode = detail.payload?.mode ?? null
    if (capturedMode) {
      modeListeners.forEach(fn => fn(capturedMode!))
    }
  }
})

function Root() {
  const [mode, setMode] = useState<string | null>(capturedMode)

  useEffect(() => {
    // If mode arrived between module init and this effect, pick it up
    if (capturedMode && capturedMode !== mode) {
      setMode(capturedMode)
    }
    // Subscribe to future changes
    modeListeners.add(setMode)
    return () => { modeListeners.delete(setMode) }
  }, [])

  if (!mode) return null
  if (mode === 'architecture') return <div style={{ height: '100vh', display: 'flex', flexDirection: 'column' }}><ArchitectureView /></div>
  if (mode === 'playbook') return <div style={{ height: '100vh', display: 'flex', flexDirection: 'column' }}><PlaybookDagView /></div>
  return <App />
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
)
