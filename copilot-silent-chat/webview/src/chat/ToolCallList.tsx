import type { ToolCall } from '../types'
import ToolCallCard from './ToolCallCard'

interface ToolCallListProps {
  toolCalls: ToolCall[]
}

export default function ToolCallList({ toolCalls }: ToolCallListProps) {
  if (toolCalls.length === 0) return null

  return (
    <div className="panel-section">
      <div className="panel-title">Tool Calls</div>
      {toolCalls.map(tc => (
        <ToolCallCard key={tc.toolCallId} toolCall={tc} />
      ))}
    </div>
  )
}
