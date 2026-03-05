/** Mirrors SilentChatEvent from the Kotlin side */

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
  sessionId?: string
  isStreaming?: boolean
}

export interface ToolCall {
  toolCallId: string
  toolName: string
  toolType?: string
  input?: Record<string, unknown>
  inputMessage?: string
  status: string
  result?: Array<{ type?: string; value?: unknown }>
  error?: string
  durationMs?: number
  sessionId: string
  turnId?: string
}

export interface StepInfo {
  id: string
  title: string
  description?: string
  status: string
}

export interface ModelOption {
  id: string
  name: string
}

export interface ModeOption {
  id: string
  name: string
  kind: string
}
