import React, { useState, useEffect } from 'react'
import { subscribe, postMessage, type JcefDataEvent } from '../bridge'
import type { PlaybookStep, PlaybookFileSummary, PlaybookProgress, StepState } from './types'
import './playbook.css'
import '../explorer/explorer.css'

/**
 * Tool-window tab: lists playbook JSON files, shows step detail when a node
 * is clicked in the DAG editor, and displays live execution progress.
 */

type StepDetail = {
  step: PlaybookStep
  allSteps: PlaybookStep[]
  playbookName: string
  stepStates?: Record<string, StepState>
  stepResults?: Record<string, string>
}

function highlightVarRefs(prompt: string, resolvedVars?: Set<string>): (string | React.ReactElement)[] {
  const parts: (string | React.ReactElement)[] = []
  const regex = /\{([\w_-]+)\}/g
  let last = 0
  let match: RegExpExecArray | null
  while ((match = regex.exec(prompt)) !== null) {
    if (match.index > last) parts.push(prompt.slice(last, match.index))
    const varName = match[1]
    const isResolved = resolvedVars?.has(varName)
    parts.push(
      <span
        key={match.index}
        className="pb-var-ref"
        style={isResolved ? { background: 'rgba(80,250,123,0.15)', color: '#50fa7b' } : undefined}
        title={isResolved ? 'Resolved' : 'Unresolved — will be substituted at runtime'}
      >
        {match[0]}
      </span>
    )
    last = match.index + match[0].length
  }
  if (last < prompt.length) parts.push(prompt.slice(last))
  return parts
}

export default function PlaybookView() {
  const [files, setFiles] = useState<PlaybookFileSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [detail, setDetail] = useState<StepDetail | null>(null)
  const [progress, setProgress] = useState<PlaybookProgress | null>(null)

  useEffect(() => {
    const unsub = subscribe((data: JcefDataEvent) => {
      if (data.channel === 'playbook-files') {
        setFiles(data.payload as PlaybookFileSummary[])
        setLoading(false)
      }
      if (data.channel === 'step-detail') {
        setDetail(data.payload as StepDetail)
      }
      if (data.channel === 'playbook-progress') {
        setProgress(data.payload as PlaybookProgress)
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
    const { step, allSteps, playbookName, stepStates, stepResults } = detail
    const deps = (step.dependsOn || [])
      .map((id) => allSteps.find((s) => s.id === id))
      .filter(Boolean) as PlaybookStep[]
    const dependents = allSteps.filter((s) => (s.dependsOn || []).includes(step.id))
    const state: StepState = stepStates?.[step.id] || 'idle'
    const result = stepResults?.[step.id]

    // Build set of resolved variable names
    const resolvedVars = new Set<string>()
    if (stepResults) {
      for (const id of Object.keys(stepResults)) {
        resolvedVars.add(`step_${id}_result`)
      }
      if (Object.keys(stepResults).length > 0) resolvedVars.add('results')
    }
    // Parameters are always resolved at runtime
    // (we don't have the param values here, but they'll be substituted)

    const stateLabel: Record<StepState, string> = {
      idle: 'Pending', running: 'Running', success: 'Done', error: 'Failed',
    }
    const stateColor: Record<StepState, string> = {
      idle: '#6272a4', running: '#f1fa8c', success: '#50fa7b', error: '#ff5555',
    }

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
            <div style={{
              background: `${stateColor[state]}22`,
              color: stateColor[state],
              width: 32, height: 32, borderRadius: 8,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              flexShrink: 0,
            }}>
              {state === 'running' ? (
                <div className="pb-spinner" />
              ) : state === 'success' ? (
                <svg width="14" height="14" viewBox="0 0 13 13" fill="none">
                  <path d="M2.5 6.5L5.5 9.5L10.5 3.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              ) : state === 'error' ? (
                <svg width="14" height="14" viewBox="0 0 13 13" fill="none">
                  <path d="M3 3l7 7M10 3l-7 7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
                </svg>
              ) : (
                <svg width="14" height="14" viewBox="0 0 12 12" fill="none">
                  <circle cx="6" cy="6" r="5" stroke="currentColor" strokeWidth="1.5" />
                </svg>
              )}
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ color: '#f8f8f2', fontSize: 16, fontWeight: 600 }}>{step.name}</div>
              <div style={{ color: '#6272a4', fontSize: 12, display: 'flex', gap: 8, marginTop: 2 }}>
                <span>ID: {step.id}</span>
                {step.agentMode && <span className="pb-agent-badge">Agent</span>}
              </div>
            </div>
            <span style={{
              padding: '3px 8px', borderRadius: 4, fontSize: '0.7rem', fontWeight: 600,
              background: `${stateColor[state]}22`, color: stateColor[state],
            }}>
              {stateLabel[state]}
            </span>
          </div>

          {/* Prompt with variable highlighting */}
          <div className="c4-sidebar-section">
            <div className="c4-sidebar-section-title">Prompt</div>
            <div className="pb-prompt" style={{ fontSize: '0.8rem', lineHeight: 1.6 }}>
              {highlightVarRefs(step.prompt, resolvedVars)}
            </div>
          </div>

          {/* Variables */}
          {(() => {
            const vars = extractVarRefs(step.prompt)
            if (vars.length === 0) return null
            return (
              <div className="c4-sidebar-section">
                <div className="c4-sidebar-section-title">Variables ({vars.length})</div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  {vars.map((v) => {
                    const isResolved = resolvedVars.has(v)
                    return (
                      <div key={v} style={{
                        display: 'flex', alignItems: 'center', gap: 8,
                        padding: '4px 8px', borderRadius: 4,
                        background: isResolved ? 'rgba(80,250,123,0.06)' : 'rgba(255,184,108,0.06)',
                      }}>
                        <div style={{
                          width: 6, height: 6, borderRadius: '50%',
                          background: isResolved ? '#50fa7b' : '#ffb86c',
                        }} />
                        <code style={{
                          color: isResolved ? '#50fa7b' : '#ffb86c',
                          fontSize: '0.75rem',
                        }}>
                          {`{${v}}`}
                        </code>
                        <span style={{ color: '#6272a4', fontSize: '0.7rem', marginLeft: 'auto' }}>
                          {isResolved ? 'resolved' : 'pending'}
                        </span>
                      </div>
                    )
                  })}
                </div>
              </div>
            )
          })()}

          {/* Result */}
          {result && (
            <div className="c4-sidebar-section">
              <div className="c4-sidebar-section-title">Result</div>
              <div className="pb-prompt" style={{ fontSize: '0.78rem', lineHeight: 1.5, maxHeight: 300, overflow: 'auto' }}>
                {result}
              </div>
            </div>
          )}

          {/* Dependencies */}
          {deps.length > 0 && (
            <div className="c4-sidebar-section">
              <div className="c4-sidebar-section-title">Depends On ({deps.length})</div>
              {deps.map((dep) => {
                const depState = stepStates?.[dep.id] || 'idle'
                return (
                  <div key={dep.id} className="c4-relationship">
                    <span className="c4-rel-direction" style={{ color: stateColor[depState] }}>{'\u2190'}</span>
                    <span className="c4-rel-target">{dep.name}</span>
                    <span style={{
                      fontSize: '0.65rem', padding: '1px 6px', borderRadius: 3,
                      background: `${stateColor[depState]}22`, color: stateColor[depState],
                    }}>
                      {stateLabel[depState]}
                    </span>
                  </div>
                )
              })}
            </div>
          )}

          {/* Dependents */}
          {dependents.length > 0 && (
            <div className="c4-sidebar-section">
              <div className="c4-sidebar-section-title">Depended On By ({dependents.length})</div>
              {dependents.map((dep) => {
                const depState = stepStates?.[dep.id] || 'idle'
                return (
                  <div key={dep.id} className="c4-relationship">
                    <span className="c4-rel-direction" style={{ color: stateColor[depState] }}>{'\u2192'}</span>
                    <span className="c4-rel-target">{dep.name}</span>
                    <span style={{
                      fontSize: '0.65rem', padding: '1px 6px', borderRadius: 3,
                      background: `${stateColor[depState]}22`, color: stateColor[depState],
                    }}>
                      {stateLabel[depState]}
                    </span>
                  </div>
                )
              })}
            </div>
          )}

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

  // ── File list view with progress ──
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

      {/* Live execution progress bar */}
      {progress && progress.steps.some((s) => s.state !== 'idle') && (
        <div style={{ padding: '10px 16px', borderBottom: '1px solid #44475a' }}>
          <div style={{ color: '#f8f8f2', fontSize: '0.78rem', fontWeight: 600, marginBottom: 8 }}>
            {progress.playbookName}
          </div>
          <div style={{ display: 'flex', gap: 4 }}>
            {progress.steps.map((s) => {
              const color = s.state === 'success' ? '#50fa7b'
                : s.state === 'error' ? '#ff5555'
                : s.state === 'running' ? '#f1fa8c'
                : '#44475a'
              return (
                <div key={s.id} style={{
                  flex: 1, height: 4, borderRadius: 2, background: color,
                  transition: 'background 0.3s',
                }} title={`${s.name}: ${s.state}`} />
              )
            })}
          </div>
          <div style={{ color: '#6272a4', fontSize: '0.7rem', marginTop: 4 }}>
            {progress.steps.filter((s) => s.state === 'success').length}/{progress.steps.length} complete
          </div>
        </div>
      )}

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

function extractVarRefs(prompt: string): string[] {
  const refs: string[] = []
  const regex = /\{([\w_-]+)\}/g
  let match: RegExpExecArray | null
  while ((match = regex.exec(prompt)) !== null) {
    if (!refs.includes(match[1])) refs.push(match[1])
  }
  return refs
}
