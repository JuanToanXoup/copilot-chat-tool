import { useState, useEffect } from 'react'
import { subscribe, postMessage, type JcefDataEvent } from './bridge'
import MarkdownRenderer from './MarkdownRenderer'

interface SessionEntryData {
  id: string
  entryType: 'message' | 'tool_call' | 'step'
  turnId?: string
  startTime: number
  endTime?: number
  status: string
  durationMs?: number
  // message fields
  prompt?: string
  response?: string
  replyLength?: number
  // tool_call fields
  toolName?: string
  toolType?: string
  input?: string
  inputMessage?: string
  output?: string
  error?: string
  progressMessage?: string
  // step fields
  title?: string
  description?: string
}

interface ChatSessionData {
  sessionId: string
  playbookId?: string
  startTime: number
  endTime?: number
  status: string
  durationMs?: number
  entries: SessionEntryData[]
}

interface PlaybookRunData {
  id: string
  startTime: number
  endTime?: number
  durationMs?: number
  chatSessions: ChatSessionData[]
}

function timeAgo(ts: number): string {
  const diff = Date.now() - ts
  if (diff < 60000) return 'just now'
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`
  return new Date(ts).toLocaleDateString()
}

function fmtTime(ts?: number): string {
  if (ts == null) return '—'
  return new Date(ts).toLocaleTimeString()
}

function dur(ms?: number): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${(ms / 60000).toFixed(1)}m`
}

function statusDot(status: string): string {
  switch (status.toUpperCase()) {
    case 'ACTIVE': case 'RUNNING': case 'STREAMING': return '●'
    case 'COMPLETED': case 'COMPLETE': return '✓'
    case 'ERROR': case 'FAILED': return '✗'
    case 'CANCELLED': return '⊘'
    default: return '·'
  }
}

function statusClass(status: string): string {
  switch (status.toUpperCase()) {
    case 'ACTIVE': case 'RUNNING': case 'STREAMING': return 'st-active'
    case 'COMPLETED': case 'COMPLETE': return 'st-ok'
    case 'ERROR': case 'FAILED': return 'st-err'
    case 'CANCELLED': return 'st-cancel'
    default: return ''
  }
}

function Collapsible({ label, children, defaultOpen = false }: { label: string; children: React.ReactNode; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <div className="st-collapse">
      <div className="st-collapse-head" onClick={() => setOpen(!open)}>
        <span className="st-arrow">{open ? '▾' : '▸'}</span>
        <span>{label}</span>
      </div>
      {open && <div className="st-collapse-body">{children}</div>}
    </div>
  )
}

interface SessionsViewProps {
  onOpenSession?: (sessionId: string) => void
}

export default function SessionsView({ onOpenSession }: SessionsViewProps) {
  const [sessions, setSessions] = useState<ChatSessionData[]>([])
  const [playbooks, setPlaybooks] = useState<PlaybookRunData[]>([])
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [tab, setTab] = useState<'sessions' | 'playbooks'>('sessions')

  function toggle(id: string) {
    setExpanded(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }

  function fetchData() {
    postMessage({ command: 'getSessions' })
    postMessage({ command: 'getPlaybooks' })
  }

  useEffect(() => {
    const unsub = subscribe((data: JcefDataEvent) => {
      switch (data.channel) {
        case 'sessions': setSessions(data.payload as ChatSessionData[]); break
        case 'playbooks': setPlaybooks(data.payload as PlaybookRunData[]); break
        case 'event': {
          const event = (data.payload as any)?.event
          if (event === 'Complete' || event === 'Error' || event === 'Cancel' || event === 'SessionReady') {
            fetchData()
          }
          break
        }
      }
    })
    const onReady = () => fetchData()
    if (window.__bridge) onReady()
    else window.addEventListener('bridge-ready', onReady, { once: true })
    return () => { unsub(); window.removeEventListener('bridge-ready', onReady) }
  }, [])

  function renderField(label: string, content: string, mode: 'pre' | 'md' | 'err') {
    const short = content.length <= 120
    if (short && mode === 'pre') {
      return <div className="st-inline-field"><span className="st-field-label-inline">{label}:</span> <span className="st-field-inline-val">{content}</span></div>
    }
    return (
      <Collapsible label={`${label}${mode !== 'md' && short ? ': ' + content : ` (${content.length} chars)`}`}>
        {mode === 'md' ? (
          <div className="st-field-md"><MarkdownRenderer content={content} /></div>
        ) : mode === 'err' ? (
          <div className="st-field-value st-field-err">{content}</div>
        ) : (
          <pre className="st-field-value">{content}</pre>
        )}
      </Collapsible>
    )
  }

  interface Turn {
    message: SessionEntryData
    children: SessionEntryData[]  // tool_calls and steps
  }

  /** Group entries into turns: each message entry + its trailing tool_call/step entries. */
  function groupTurns(entries: SessionEntryData[]): Turn[] {
    const turns: Turn[] = []
    let current: Turn | null = null
    for (const e of entries) {
      if (e.entryType === 'message') {
        if (current) turns.push(current)
        current = { message: e, children: [] }
      } else if (current) {
        current.children.push(e)
      }
    }
    if (current) turns.push(current)
    return turns
  }

  function renderChildEntry(e: SessionEntryData) {
    if (e.entryType === 'step') return renderStepEntry(e)
    return renderToolCallEntry(e)
  }

  function renderStepEntry(e: SessionEntryData) {
    const isOpen = expanded.has(e.id)
    const label = `${statusDot(e.status)} ${e.title || 'Step'} — ${fmtTime(e.startTime)}`
    return (
      <div key={e.id} className="st-entry">
        <div className={`st-entry-row ${statusClass(e.status)}`} onClick={e.description ? () => toggle(e.id) : undefined}>
          {e.description && <span className="st-arrow">{isOpen ? '▾' : '▸'}</span>}
          <span>{label}</span>
        </div>
        {isOpen && e.description && (
          <div className="st-entry-detail">
            <pre className="st-field-value">{e.description}</pre>
          </div>
        )}
      </div>
    )
  }

  function renderToolCallEntry(e: SessionEntryData) {
    const isOpen = expanded.has(e.id)
    const label = `${statusDot(e.status)} ${e.toolName || 'Tool'}${e.toolType ? ` [${e.toolType}]` : ''} — ${dur(e.durationMs)} — ${fmtTime(e.startTime)}${e.endTime ? ' → ' + fmtTime(e.endTime) : ''}`
    return (
      <div key={e.id} className="st-entry">
        <div className={`st-entry-row ${statusClass(e.status)}`} onClick={() => toggle(e.id)}>
          <span className="st-arrow">{isOpen ? '▾' : '▸'}</span>
          <span>{label}</span>
        </div>
        {isOpen && (
          <div className="st-entry-detail">
            {e.inputMessage && <div className="st-inline-field"><span className="st-field-label-inline">Message:</span> {e.inputMessage}</div>}
            {e.input && renderField('Input', e.input, 'pre')}
            {e.output && renderField('Output', e.output, 'pre')}
            {e.progressMessage && <div className="st-inline-field"><span className="st-field-label-inline">Progress:</span> {e.progressMessage}</div>}
            {e.error && renderField('Error', e.error, 'err')}
          </div>
        )}
      </div>
    )
  }

  function renderCopilotEntry(e: SessionEntryData) {
    const copilotId = e.id + '-copilot'
    const isOpen = expanded.has(copilotId)
    const label = `${statusDot(e.status)} Copilot — ${dur(e.durationMs)}`
    return (
      <div className="st-entry">
        <div className={`st-entry-row ${statusClass(e.status)}`} onClick={() => toggle(copilotId)}>
          <span className="st-arrow">{isOpen ? '▾' : '▸'}</span>
          <span>{label}</span>
        </div>
        {isOpen && (
          <div className="st-entry-detail">
            {e.response && renderField('Output', e.response, 'md')}
          </div>
        )}
      </div>
    )
  }

  function renderTurn(turn: Turn) {
    const e = turn.message
    const isOpen = expanded.has(e.id)
    const label = `${statusDot(e.status)} Prompt — ${dur(e.durationMs)} — ${fmtTime(e.startTime)}${e.endTime ? ' → ' + fmtTime(e.endTime) : ''}`
    return (
      <div key={e.id} className="st-entry">
        <div className={`st-entry-row ${statusClass(e.status)}`} onClick={() => toggle(e.id)}>
          <span className="st-arrow">{isOpen ? '▾' : '▸'}</span>
          <span>{label}</span>
        </div>
        {isOpen && (
          <div className="st-entry-detail">
            {e.prompt && <div className="st-prompt-text">{e.prompt}</div>}
            {turn.children.map(renderChildEntry)}
            {e.response && renderCopilotEntry(e)}
          </div>
        )}
      </div>
    )
  }

  function renderSession(s: ChatSessionData) {
    const isOpen = expanded.has(s.sessionId)
    const msgs = s.entries.filter(e => e.entryType === 'message').length
    const tools = s.entries.filter(e => e.entryType === 'tool_call').length
    const label = `${statusDot(s.status)} ${s.sessionId} — ${msgs}m ${tools}t — ${dur(s.durationMs)} — ${s.status} — ${fmtTime(s.startTime)}${s.endTime ? ' → ' + fmtTime(s.endTime) : ''} — ${timeAgo(s.startTime)}`
    return (
      <div key={s.sessionId} className={`st-session ${isOpen ? 'st-session-open' : ''}`}>
        <div className={`st-row ${statusClass(s.status)}`} onClick={() => toggle(s.sessionId)}>
          <span className="st-arrow">{isOpen ? '▾' : '▸'}</span>
          <span>{label}</span>
          {onOpenSession && (
            <button className="st-open-btn" onClick={(e) => { e.stopPropagation(); onOpenSession(s.sessionId) }}>Open</button>
          )}
        </div>
        {isOpen && (
          <div className="st-session-body">
            {s.playbookId && <div className="st-inline-field"><span className="st-field-label-inline">Playbook:</span> {s.playbookId}</div>}
            {s.entries.length === 0
              ? <div className="st-no-entries">No entries</div>
              : groupTurns(s.entries).map(renderTurn)}
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="st-container">
      <div className="st-bar">
        <button className={`sv-tab ${tab === 'sessions' ? 'sv-tab-active' : ''}`} onClick={() => setTab('sessions')}>
          Sessions ({sessions.length})
        </button>
        <button className={`sv-tab ${tab === 'playbooks' ? 'sv-tab-active' : ''}`} onClick={() => setTab('playbooks')}>
          Playbooks ({playbooks.length})
        </button>
      </div>
      <div className="st-list">
        {tab === 'sessions' && (sessions.length === 0
          ? <div className="st-empty">No sessions yet.</div>
          : sessions.map(renderSession)
        )}
        {tab === 'playbooks' && (playbooks.length === 0
          ? <div className="st-empty">No playbooks yet.</div>
          : playbooks.map(pb => {
            const isOpen = expanded.has(pb.id)
            const label = `▶ ${pb.id} — ${pb.chatSessions.length} sessions — ${dur(pb.durationMs)} — ${timeAgo(pb.startTime)}`
            return (
              <div key={pb.id} className="st-session">
                <div className="st-row" onClick={() => toggle(pb.id)}>
                  <span className="st-arrow">{isOpen ? '▾' : '▸'}</span>
                  <span>{label}</span>
                </div>
                {isOpen && (
                  <div className="st-nested">
                    {pb.chatSessions.length === 0
                      ? <div className="st-no-entries">No sessions</div>
                      : pb.chatSessions.map(renderSession)}
                  </div>
                )}
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
