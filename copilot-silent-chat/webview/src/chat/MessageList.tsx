import type { RefObject } from 'react'
import type { ChatMessage, ToolCall, StepInfo } from '../types'
import MarkdownRenderer from '../MarkdownRenderer'
import ToolCallCard from './ToolCallCard'
import { statusIcon } from './statusIcon'

interface MessageListProps {
  messages: ChatMessage[]
  toolCalls: ToolCall[]
  steps: StepInfo[]
  messagesEndRef: RefObject<HTMLDivElement | null>
}

export default function MessageList({ messages, toolCalls, steps, messagesEndRef }: MessageListProps) {
  return (
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
      {steps.map(s => (
        <div key={s.id} className={`step step-${s.status}`}>
          <span className="step-status">{statusIcon(s.status)}</span>
          <span className="step-title">{s.title}</span>
        </div>
      ))}
      {toolCalls.map(tc => (
        <ToolCallCard key={tc.toolCallId} toolCall={tc} />
      ))}
      <div ref={messagesEndRef} />
    </div>
  )
}
