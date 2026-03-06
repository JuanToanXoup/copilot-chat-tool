import { useState } from 'react'
import type { ToolCall } from '../types'
import { statusIcon } from './statusIcon'

interface ToolCallCardProps {
  toolCall: ToolCall
}

export default function ToolCallCard({ toolCall: tc }: ToolCallCardProps) {
  const [open, setOpen] = useState(false)
  const hasDetails = !!(tc.input || (tc.result && tc.result.length > 0) || tc.error)

  return (
    <div className={`tool-call tool-call-${tc.status}`}>
      <div className="tool-call-header" onClick={hasDetails ? () => setOpen(!open) : undefined} style={hasDetails ? { cursor: 'pointer' } : undefined}>
        {hasDetails && <span className="tool-call-arrow">{open ? '▾' : '▸'}</span>}
        <span className="tool-call-status">{statusIcon(tc.status)}</span>
        <span className="tool-call-name">{tc.toolName}</span>
        {tc.durationMs != null && (
          <span className="tool-call-duration">{tc.durationMs}ms</span>
        )}
      </div>
      {open && (
        <div className="tool-call-body">
          {tc.input && (
            <pre className="tool-call-detail">{JSON.stringify(tc.input, null, 2)}</pre>
          )}
          {tc.result && tc.result.length > 0 && (
            <pre className="tool-call-detail tool-call-result">
              {tc.result.map(r => typeof r.value === 'string' ? r.value : JSON.stringify(r.value, null, 2)).join('\n')}
            </pre>
          )}
          {tc.error && (
            <div className="tool-call-error">{tc.error}</div>
          )}
        </div>
      )}
    </div>
  )
}
