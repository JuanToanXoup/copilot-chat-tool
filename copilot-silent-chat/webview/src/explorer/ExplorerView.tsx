import { useState, useEffect } from 'react'
import { subscribe, postMessage, type JcefDataEvent } from '../bridge'
import type { C4Node, C4Edge, ArchitectureFileSummary } from './types'
import './explorer.css'

/**
 * Explorer tab in the tool window.
 * - Lists .c4.json files from .citi-ai/architecture/
 * - Shows node detail when a node is clicked in the file editor graph
 */

const typeStyle: Record<string, { accent: string }> = {
  system:    { accent: '#58a6ff' },
  container: { accent: '#2ea043' },
  component: { accent: '#d29922' },
  code:      { accent: '#bc8cff' },
}

const levelLabels: Record<number, string> = {
  1: 'System Context',
  2: 'Containers',
  3: 'Components',
  4: 'Code',
}

const levelColors: Record<number, string> = {
  1: '#58a6ff',
  2: '#2ea043',
  3: '#d29922',
  4: '#bc8cff',
}

type NodeDetail = {
  node: C4Node
  edges: C4Edge[]
  allNodes: C4Node[]
  basePath: string
}

interface ExplorerViewProps {
  onOpenSession?: (sessionId: string) => void
}

export default function ExplorerView({ onOpenSession: _ }: ExplorerViewProps) {
  const [files, setFiles] = useState<ArchitectureFileSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [detail, setDetail] = useState<NodeDetail | null>(null)

  useEffect(() => {
    let responded = false
    const unsub = subscribe((data: JcefDataEvent) => {
      if (data.channel === 'architecture-files') {
        responded = true
        setFiles(data.payload as ArchitectureFileSummary[])
        setLoading(false)
      }
      if (data.channel === 'node-detail') {
        setDetail(data.payload as NodeDetail)
      }
    })
    postMessage({ command: 'listArchitectureFiles' })
    // Retry once after a short delay if no response (bridge may not have been ready)
    const retryTimer = setTimeout(() => {
      if (!responded) {
        postMessage({ command: 'listArchitectureFiles' })
      }
    }, 500)
    return () => { unsub(); clearTimeout(retryTimer) }
  }, [])

  const refresh = () => {
    setLoading(true)
    postMessage({ command: 'listArchitectureFiles' })
  }

  // ── Node detail view ──
  if (detail) {
    const { node, edges, allNodes, basePath } = detail
    const accent = (typeStyle[node.type] || typeStyle.system).accent

    return (
      <div className="ex-container" style={{ padding: 0 }}>
        <div className="c4-breadcrumb">
          <button className="c4-crumb-btn" onClick={() => setDetail(null)}>
            &larr; Files
          </button>
          <span className="c4-crumb-sep">/</span>
          <span className="c4-crumb-btn c4-crumb-active">{node.label}</span>
        </div>

        <div style={{ flex: 1, overflow: 'auto', padding: 16, display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* Header */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span className="c4-node-badge" style={{ background: accent, width: 28, height: 28, fontSize: 14 }}>
              {node.type[0].toUpperCase()}
            </span>
            <div>
              <div className="c4-node-label" style={{ fontSize: 18 }}>{node.label}</div>
              {node.technology && (
                <div className="c4-node-tech" style={{ fontSize: 12 }}>[{node.technology}]</div>
              )}
            </div>
          </div>

          {/* Description */}
          <div className="c4-sidebar-desc">{node.description}</div>

          {node.technology && (
            <div className="c4-sidebar-tech">{node.technology}</div>
          )}

          {/* Relationships */}
          {edges.length > 0 && (
            <div className="c4-sidebar-section">
              <div className="c4-sidebar-section-title">Relationships</div>
              {edges.map((e, i) => {
                const isSource = e.source === node.id
                const otherNode = allNodes.find(
                  (n) => n.id === (isSource ? e.target : e.source),
                )
                return (
                  <div key={i} className="c4-relationship">
                    <span className="c4-rel-direction">{isSource ? '\u2192' : '\u2190'}</span>
                    <span className="c4-rel-target">{otherNode?.label || (isSource ? e.target : e.source)}</span>
                    <span className="c4-rel-label">{e.label}</span>
                  </div>
                )
              })}
            </div>
          )}

          {/* Actions */}
          <div className="c4-sidebar-actions">
            {node.childFile && (
              <button
                className="c4-action-primary"
                onClick={() => postMessage({ command: 'openFile', path: basePath + '/' + node.childFile })}
              >
                Zoom In
              </button>
            )}
            <button
              className="c4-action-secondary"
              onClick={() => postMessage({ command: 'askAbout', label: node.label })}
            >
              Ask AI
            </button>
          </div>
        </div>
      </div>
    )
  }

  // ── File list view ──
  return (
    <div className="ex-container" style={{ padding: 0 }}>
      <div className="c4-breadcrumb">
        <span className="c4-crumb-btn c4-crumb-active">Architecture</span>
        <div className="c4-breadcrumb-right">
          <span className="c4-node-count">{files.length} files</span>
          <button className="ex-analyze-btn" onClick={refresh}>
            Refresh
          </button>
          <button
            className="ex-analyze-btn"
            onClick={() => postMessage({ command: 'analyzeProject' })}
          >
            Generate
          </button>
        </div>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: 16 }}>
        {loading ? (
          <div style={{ color: '#8b949e', textAlign: 'center', padding: 32 }}>
            Loading...
          </div>
        ) : files.length === 0 ? (
          <div className="c4-sidebar-empty">
            <div className="c4-sidebar-empty-icon">{'\u{1F4C1}'}</div>
            <div className="c4-sidebar-empty-title">No architecture files</div>
            <div className="c4-sidebar-empty-hint">
              Click "Generate" to analyze your project and create C4 architecture diagrams
              in <code style={{ color: '#58a6ff' }}>.citi-ai/architecture/</code>
            </div>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {files.map((f) => (
              <button
                key={f.filePath}
                className="c4-node"
                style={{
                  cursor: 'pointer',
                  textAlign: 'left',
                  width: '100%',
                  borderLeftColor: levelColors[f.level] || '#30363d',
                  borderLeftWidth: 3,
                }}
                onClick={() => postMessage({ command: 'openFile', path: f.filePath })}
              >
                <div className="c4-node-header">
                  <span
                    className="c4-node-badge"
                    style={{ background: levelColors[f.level] || '#58a6ff' }}
                  >
                    L{f.level}
                  </span>
                  <span className="c4-node-label">{f.title}</span>
                  <span className="c4-node-drillable">&rarr;</span>
                </div>
                <div className="c4-node-desc" style={{ marginBottom: 4 }}>
                  {levelLabels[f.level] || 'Level ' + f.level} &middot; {f.nodeCount} nodes
                </div>
                <div className="c4-node-tech">{f.fileName}</div>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
