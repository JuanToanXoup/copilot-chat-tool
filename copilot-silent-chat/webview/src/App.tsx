import { useState, useEffect, useRef, useCallback } from 'react'
import { subscribe, sendMessage, stopGeneration, requestModels, requestModes, type JcefDataEvent } from './bridge'
import type { ChatMessage, ToolCall, StepInfo, ModelOption, ModeOption } from './types'
import MarkdownRenderer from './MarkdownRenderer'
import './style.css'

let messageIdCounter = 0
function nextId(): string {
  return `msg-${++messageIdCounter}-${Date.now()}`
}

export default function App() {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [toolCalls, setToolCalls] = useState<ToolCall[]>([])
  const [steps, setSteps] = useState<StepInfo[]>([])
  const [models, setModels] = useState<ModelOption[]>([])
  const [modes, setModes] = useState<ModeOption[]>([])
  const [selectedModelId, setSelectedModelId] = useState<string>('')
  const [selectedModeId, setSelectedModeId] = useState<string>('')
  const [currentSessionId, setCurrentSessionId] = useState<string | undefined>()
  const [isLoading, setIsLoading] = useState(false)
  const [input, setInput] = useState('')
  const [newSession, setNewSession] = useState(false)
  const [silent, setSilent] = useState(true)

  const messagesEndRef = useRef<HTMLDivElement>(null)
  const currentAssistantId = useRef<string | null>(null)

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [])

  useEffect(scrollToBottom, [messages, scrollToBottom])

  // Subscribe to bridge events via jcef-data CustomEvent
  useEffect(() => {
    const unsub = subscribe((data: JcefDataEvent) => {
      const { channel, payload } = data

      switch (channel) {
        case 'models':
          setModels(payload as ModelOption[])
          break
        case 'modes': {
          const modesData = payload as { modes: ModeOption[]; currentModeId: string }
          setModes(modesData.modes)
          setSelectedModeId(modesData.currentModeId)
          break
        }
        case 'event':
          handleEvent(payload)
          break
      }
    })

    // Request initial data once bridge is ready
    const onReady = () => {
      requestModels()
      requestModes()
    }
    if (window.__bridge) {
      onReady()
    } else {
      window.addEventListener('bridge-ready', onReady, { once: true })
    }

    return () => {
      unsub()
      window.removeEventListener('bridge-ready', onReady)
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  function handleEvent(payload: any) {
    const event = payload.event as string

    switch (event) {
      case 'SessionReady':
        setCurrentSessionId(payload.sessionId)
        break

      case 'Begin':
        setIsLoading(true)
        setSteps([])
        setToolCalls([])
        // Create a placeholder assistant message
        const assistantId = nextId()
        currentAssistantId.current = assistantId
        setMessages(prev => [...prev, {
          id: assistantId,
          role: 'assistant',
          content: '',
          timestamp: Date.now(),
          isStreaming: true,
        }])
        break

      case 'Reply':
        if (currentAssistantId.current) {
          const accumulated = payload.accumulated as string
          setMessages(prev => prev.map(m =>
            m.id === currentAssistantId.current
              ? { ...m, content: accumulated }
              : m
          ))
        }
        break

      case 'Steps':
        setSteps(payload.steps as StepInfo[])
        break

      case 'ToolCallUpdate': {
        const tc: ToolCall = {
          toolCallId: payload.toolCallId,
          toolName: payload.toolName,
          toolType: payload.toolType,
          input: payload.input,
          inputMessage: payload.inputMessage,
          status: payload.status,
          result: payload.result,
          error: payload.error,
          durationMs: payload.durationMs,
          sessionId: payload.sessionId,
          turnId: payload.turnId,
        }
        setToolCalls(prev => {
          const idx = prev.findIndex(t => t.toolCallId === tc.toolCallId)
          if (idx >= 0) {
            const next = [...prev]
            next[idx] = tc
            return next
          }
          return [...prev, tc]
        })
        break
      }

      case 'Complete':
      case 'Error':
      case 'Cancel': {
        setIsLoading(false)
        const finishedId = currentAssistantId.current
        currentAssistantId.current = null

        // Build override for the active assistant message (if any)
        setMessages(prev => prev.map(m => {
          if (!m.isStreaming) return m
          if (m.id !== finishedId) return { ...m, isStreaming: false }
          if (event === 'Complete') {
            const fullReply = payload.fullReply as string
            return { ...m, content: fullReply || m.content, isStreaming: false, sessionId: currentSessionId }
          }
          if (event === 'Error') {
            return { ...m, content: `**Error:** ${payload.message}`, isStreaming: false }
          }
          return { ...m, isStreaming: false }
        }))
        break
      }
    }
  }

  function handleStop() {
    stopGeneration()
  }

  function handleSend() {
    const text = input.trim()
    if (!text || isLoading) return

    const userMsg: ChatMessage = {
      id: nextId(),
      role: 'user',
      content: text,
      timestamp: Date.now(),
    }
    if (newSession) {
      setMessages([userMsg])
      setToolCalls([])
      setSteps([])
    } else {
      setMessages(prev => [...prev, userMsg])
    }
    setInput('')

    sendMessage({
      message: text,
      modelId: selectedModelId || undefined,
      modeId: selectedModeId || undefined,
      sessionId: newSession ? undefined : currentSessionId,
      newSession,
      silent,
    })
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  return (
    <div className="app">
      {/* Toolbar */}
      <div className="toolbar">
        <select
          value={selectedModeId}
          onChange={e => setSelectedModeId(e.target.value)}
          title="Chat Mode"
        >
          {modes.map(m => (
            <option key={m.id} value={m.id}>{m.name}</option>
          ))}
        </select>

        <select
          value={selectedModelId}
          onChange={e => setSelectedModelId(e.target.value)}
          title="Model"
        >
          <option value="">(Default)</option>
          {models.map(m => (
            <option key={m.id} value={m.id}>{m.name}</option>
          ))}
        </select>

        <label className="checkbox-label" title="Start a new session for the next message">
          <input
            type="checkbox"
            checked={newSession}
            onChange={e => setNewSession(e.target.checked)}
          />
          New Session
        </label>

        <label className="checkbox-label" title="Silent mode — don't open the Copilot tool window">
          <input
            type="checkbox"
            checked={silent}
            onChange={e => setSilent(e.target.checked)}
          />
          Silent
        </label>

        {currentSessionId && (
          <span className="session-badge" title={currentSessionId}>
            {currentSessionId}
          </span>
        )}
      </div>

      {/* Main content area */}
      <div className="main">
        {/* Messages */}
        <div className="messages">
          {messages.length === 0 && (
            <div className="empty-state">Send a message to start a conversation.</div>
          )}
          {messages.map(msg => (
            <div key={msg.id} className={`message message-${msg.role}`}>
              <div className="message-role">{msg.role === 'user' ? 'You' : 'Copilot'}</div>
              <div className="message-content">
                {msg.content ? (
                  <MarkdownRenderer content={msg.content} />
                ) : (
                  msg.isStreaming ? '…' : ''
                )}
                {msg.isStreaming && <span className="cursor">▋</span>}
              </div>
            </div>
          ))}
          <div ref={messagesEndRef} />
        </div>

        {/* Side panel — tool calls & steps */}
        {(toolCalls.length > 0 || steps.length > 0) && (
          <div className="side-panel">
            {steps.length > 0 && (
              <div className="panel-section">
                <div className="panel-title">Steps</div>
                {steps.map(s => (
                  <div key={s.id} className={`step step-${s.status}`}>
                    <span className="step-status">{statusIcon(s.status)}</span>
                    <span className="step-title">{s.title}</span>
                  </div>
                ))}
              </div>
            )}
            {toolCalls.length > 0 && (
              <div className="panel-section">
                <div className="panel-title">Tool Calls</div>
                {toolCalls.map(tc => (
                  <div key={tc.toolCallId} className={`tool-call tool-call-${tc.status}`}>
                    <div className="tool-call-header">
                      <span className="tool-call-status">{statusIcon(tc.status)}</span>
                      <span className="tool-call-name">{tc.toolName}</span>
                      {tc.durationMs != null && (
                        <span className="tool-call-duration">{tc.durationMs}ms</span>
                      )}
                    </div>
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
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Input area */}
      <div className="input-area">
        <textarea
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Type a message… (Enter to send, Shift+Enter for newline)"
          rows={2}
          disabled={isLoading}
        />
        <button
          onClick={isLoading ? handleStop : handleSend}
          disabled={!isLoading && !input.trim()}
          className={isLoading ? 'stop' : ''}
        >
          {isLoading ? 'Stop' : 'Send'}
        </button>
      </div>
    </div>
  )
}

function statusIcon(status: string): string {
  switch (status) {
    case 'running': return '⟳'
    case 'completed': return '✓'
    case 'failed': return '✗'
    case 'cancelled': return '⊘'
    default: return '·'
  }
}
