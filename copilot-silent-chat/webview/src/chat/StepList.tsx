import type { StepInfo } from '../types'
import { statusIcon } from './statusIcon'

interface StepListProps {
  steps: StepInfo[]
}

export default function StepList({ steps }: StepListProps) {
  if (steps.length === 0) return null

  return (
    <div className="panel-section">
      <div className="panel-title">Steps</div>
      {steps.map(s => (
        <div key={s.id} className={`step step-${s.status}`}>
          <span className="step-status">{statusIcon(s.status)}</span>
          <span className="step-title">{s.title}</span>
        </div>
      ))}
    </div>
  )
}
