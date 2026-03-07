import { useState, useEffect } from 'react'
import { subscribe, postMessage, type JcefDataEvent } from '../bridge'
import type { PlaybookStep, PlaybookFileSummary } from './types'
import './playbook.css'
import '../explorer/explorer.css'

/**
 * Tool-window tab: lists playbook JSON files from .citi-ai/playbooks/.
 * Clicking a file opens it in the editor with the split text + DAG preview.
 * Also shows step detail when a node is clicked in the DAG file editor.
 */

type StepDetail = {
  step: PlaybookStep
  allSteps: PlaybookStep[]
  playbookName: string
}

function highlightVarRefs(prompt: string): (string | React.ReactElement)[] {
  const parts: (string | React.ReactElement)[] = []
  const regex = /\{[\w_-]+\}/g
  let last = 0
  let match: RegExpExecArray | null
  while ((match = regex.exec(prompt)) !== null) {
    if (match.index > last) parts.push(prompt.slice(last, match.index))
    parts.push(<span key={match.index} className="pb-var-ref">{match[0]}</span>)
    last = match.index + match[0].length
  }
  if (last < prompt.length) parts.push(prompt.slice(last))
  return parts
}

export default function PlaybookView() {
  const [files, setFiles] = useState<PlaybookFileSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [detail, setDetail] = useState<StepDetail | null>(null)

  useEffect(() => {
    const unsub = subscribe((data: JcefDataEvent) => {
      if (data.channel === 'playbook-files') {
        setFiles(data.payload as PlaybookFileSummary[])
        setLoading(false)
      }
      if (data.channel === 'step-detail') {
        setDetail(data.payload as StepDetail)
      }
    })
    postMessage({ command: 'listPlaybookFiles' })
    return unsub
  }, [])

  const openFile = (filePath: string) => {
    postMessage({ command: 'loadPlaybookFile', path: filePath })
  }

  // ── Step detail view ──
  if (detail) {
    const { step, allSteps, playbookName } = detail
    const deps = (step.dependsOn || [])
      .map((id) => allSteps.find((s) => s.id === id))
      .filter(Boolean) as PlaybookStep[]
    const dependents = allSteps.filter((s) => (s.dependsOn || []).includes(step.id))

    return (
      <div className="pb-container" style={{ padding: 0 }}>
        <div className="c4-breadcrumb">
          <button className="c4-crumb-btn" onClick={() => setDetail(null)}>
            &larr; Playbooks
          </button>
          <span className="c4-crumb-sep">/</span>
          <span style={{ color: '#6272a4', fontSize: '0.78rem' }}>{playbookName}</span>
          <span className="c4-crumb-sep">/</span>
          <span className="c4-crumb-btn c4-crumb-active">{step.name}</span>
        </div>

        <div style={{ flex: 1, overflow: 'auto', padding: 16, display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* Header */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div className="c4-step-icon" style={{
              background: step.id === '__synthesis__' ? 'rgba(241,250,140,0.15)' : 'rgba(189,147,249,0.15)',
              color: step.id === '__synthesis__' ? '#f1fa8c' : '#bd93f9',
              width: 32, height: 32, borderRadius: 8,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              {step.agentMode ? (
                <svg width="14" height="14" viewBox="0 0 12 12" fill="none">
                  <path d="M6 1v10M1 6h10" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                </svg>
              ) : (
                <svg width="14" height="14" viewBox="0 0 12 12" fill="none">
                  <circle cx="6" cy="6" r="5" stroke="currentColor" strokeWidth="1.5" />
                </svg>
              )}
            </div>
            <div>
              <div style={{ color: '#f8f8f2', fontSize: 16, fontWeight: 600 }}>{step.name}</div>
              <div style={{ color: '#6272a4', fontSize: 12, display: 'flex', gap: 8, marginTop: 2 }}>
                <span>ID: {step.id}</span>
                {step.agentMode && <span className="pb-agent-badge">Agent</span>}
              </div>
            </div>
          </div>

          {/* Prompt */}
          <div className="c4-sidebar-section">
            <div className="c4-sidebar-section-title">Prompt</div>
            <div className="pb-prompt" style={{ fontSize: '0.8rem', lineHeight: 1.6 }}>
              {highlightVarRefs(step.prompt)}
            </div>
          </div>

          {/* Dependencies */}
          {deps.length > 0 && (
            <div className="c4-sidebar-section">
              <div className="c4-sidebar-section-title">Depends On ({deps.length})</div>
              {deps.map((dep) => (
                <div key={dep.id} className="c4-relationship">
                  <span className="c4-rel-direction">{'\u2190'}</span>
                  <span className="c4-rel-target">{dep.name}</span>
                  <span className="c4-rel-label">{dep.id}</span>
                </div>
              ))}
            </div>
          )}

          {/* Dependents */}
          {dependents.length > 0 && (
            <div className="c4-sidebar-section">
              <div className="c4-sidebar-section-title">Depended On By ({dependents.length})</div>
              {dependents.map((dep) => (
                <div key={dep.id} className="c4-relationship">
                  <span className="c4-rel-direction">{'\u2192'}</span>
                  <span className="c4-rel-target">{dep.name}</span>
                  <span className="c4-rel-label">{dep.id}</span>
                </div>
              ))}
            </div>
          )}

          {/* Entry point indicator */}
          {(step.dependsOn || []).length === 0 && step.id !== '__synthesis__' && (
            <div style={{
              padding: '8px 12px', borderRadius: 6,
              background: 'rgba(80,250,123,0.08)', border: '1px solid rgba(80,250,123,0.2)',
              color: '#50fa7b', fontSize: '0.78rem',
            }}>
              Entry point — no dependencies
            </div>
          )}
        </div>
      </div>
    )
  }

  // ── File list view ──
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
