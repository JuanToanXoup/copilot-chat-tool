import { useState, useEffect } from 'react'
import { subscribe, postMessage, type JcefDataEvent } from '../bridge'
import type { PlaybookFileSummary } from './types'
import './playbook.css'
import '../explorer/explorer.css'

/**
 * Tool-window tab: lists playbook JSON files from .citi-ai/playbooks/.
 * Clicking a file opens it in the editor with the split text + DAG preview.
 */

export default function PlaybookView() {
  const [files, setFiles] = useState<PlaybookFileSummary[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const unsub = subscribe((data: JcefDataEvent) => {
      if (data.channel === 'playbook-files') {
        setFiles(data.payload as PlaybookFileSummary[])
        setLoading(false)
      }
    })
    postMessage({ command: 'listPlaybookFiles' })
    return unsub
  }, [])

  const openFile = (filePath: string) => {
    postMessage({ command: 'loadPlaybookFile', path: filePath })
  }

  return (
    <div className="pb-container" style={{ padding: 0 }}>
      <div className="c4-breadcrumb">
        <span className="c4-crumb-btn c4-crumb-active">Playbooks</span>
        <div className="c4-breadcrumb-right">
          <span className="c4-node-count">{files.length} files</span>
          <button
            className="ex-analyze-btn"
            onClick={() => { setLoading(true); postMessage({ command: 'listPlaybookFiles' }) }}
          >
            Refresh
          </button>
        </div>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: 16 }}>
        {loading ? (
          <div style={{ color: '#6272a4', textAlign: 'center', padding: 32 }}>Loading...</div>
        ) : files.length === 0 ? (
          <div className="c4-sidebar-empty">
            <div className="c4-sidebar-empty-title" style={{ color: '#f8f8f2' }}>No playbook files</div>
            <div className="c4-sidebar-empty-hint" style={{ color: '#6272a4' }}>
              Add playbook JSON files to <code style={{ color: '#8be9fd' }}>.citi-ai/playbooks/</code>
            </div>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {files.map((f) => (
              <button
                key={f.filePath}
                className="pb-file-card"
                onClick={() => openFile(f.filePath)}
              >
                <div className="pb-file-name">{f.name}</div>
                <div className="pb-file-meta">{f.stepCount} steps &middot; {f.fileName}</div>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
