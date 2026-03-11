import { useState, useEffect, useRef } from 'react'
import { subscribe, type JcefDataEvent } from './bridge'

interface LogEntry {
  id: number
  timestamp: number
  level: string
  tag: string
  message: string
}

let logId = 0

export default function LogsView() {
  const [logs, setLogs] = useState<LogEntry[]>([])
  const [filter, setFilter] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)
  const autoScroll = useRef(true)

  useEffect(() => {
    const unsub = subscribe((data: JcefDataEvent) => {
      if (data.channel === 'log') {
        const payload = data.payload as { level: string; tag: string; message: string }
        setLogs(prev => [...prev, {
          id: ++logId,
          timestamp: Date.now(),
          level: payload.level,
          tag: payload.tag,
          message: payload.message,
        }])
      }
    })
    return unsub
  }, [])

  useEffect(() => {
    if (autoScroll.current) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [logs])

  const filtered = filter
    ? logs.filter(l => l.tag.toLowerCase().includes(filter.toLowerCase()) || l.message.toLowerCase().includes(filter.toLowerCase()))
    : logs

  function levelClass(level: string) {
    switch (level.toUpperCase()) {
      case 'ERROR': return 'log-error'
      case 'WARN': return 'log-warn'
      case 'DEBUG': return 'log-debug'
      default: return ''
    }
  }

  function fmtTime(ts: number) {
    return new Date(ts).toLocaleTimeString(undefined, { hour12: false, fractionalSecondDigits: 3 } as any)
  }

  return (
    <div className="logs-container">
      <div className="logs-toolbar">
        <input
          className="logs-filter"
          placeholder="Filter logs…"
          value={filter}
          onChange={e => setFilter(e.target.value)}
        />
        <label className="checkbox-label">
          <input type="checkbox" checked={autoScroll.current} onChange={e => { autoScroll.current = e.target.checked }} />
          Auto-scroll
        </label>
        <button className="logs-clear" onClick={() => setLogs([])}>Clear</button>
        <span className="logs-count">{filtered.length} entries</span>
      </div>
      <div className="logs-list">
        {filtered.map(l => (
          <div key={l.id} className={`log-entry ${levelClass(l.level)}`}>
            <span className="log-time">{fmtTime(l.timestamp)}</span>
            <span className="log-level">{l.level}</span>
            <span className="log-tag">[{l.tag}]</span>
            <span className="log-msg">{l.message}</span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
    </div>
  )
}
