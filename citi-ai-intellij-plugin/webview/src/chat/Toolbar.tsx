import type { ModelOption, ModeOption } from '../types'

interface ToolbarProps {
  modes: ModeOption[]
  models: ModelOption[]
  selectedModeId: string
  selectedModelId: string
  onModeChange: (id: string) => void
  onModelChange: (id: string) => void
  newSession: boolean
  onNewSessionChange: (v: boolean) => void
  silent: boolean
  onSilentChange: (v: boolean) => void
  currentSessionId?: string
}

export default function Toolbar({
  modes, models, selectedModeId, selectedModelId,
  onModeChange, onModelChange,
  newSession, onNewSessionChange,
  silent, onSilentChange,
  currentSessionId,
}: ToolbarProps) {
  return (
    <div className="toolbar">
      <select value={selectedModeId} onChange={e => onModeChange(e.target.value)} title="Chat Mode">
        {modes.map(m => (
          <option key={m.id} value={m.id}>{m.name}</option>
        ))}
      </select>

      <select value={selectedModelId} onChange={e => onModelChange(e.target.value)} title="Model">
        <option value="">(Default)</option>
        {models.map(m => (
          <option key={m.id} value={m.id}>{m.name}</option>
        ))}
      </select>

      <label className="checkbox-label" title="Start a new session for the next message">
        <input type="checkbox" checked={newSession} onChange={e => onNewSessionChange(e.target.checked)} />
        New Session
      </label>

      <label className="checkbox-label" title="Silent mode — don't open the Copilot tool window">
        <input type="checkbox" checked={silent} onChange={e => onSilentChange(e.target.checked)} />
        Silent
      </label>

      {currentSessionId && (
        <span className="session-badge" title={currentSessionId}>
          {currentSessionId}
        </span>
      )}
    </div>
  )
}
