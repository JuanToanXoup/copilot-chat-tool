import type { ToolCall, StepInfo } from '../types'
import ToolCallList from './ToolCallList'
import StepList from './StepList'

interface SidePanelProps {
  toolCalls: ToolCall[]
  steps: StepInfo[]
}

export default function SidePanel({ toolCalls, steps }: SidePanelProps) {
  if (toolCalls.length === 0 && steps.length === 0) return null

  return (
    <div className="side-panel">
      <StepList steps={steps} />
      <ToolCallList toolCalls={toolCalls} />
    </div>
  )
}
