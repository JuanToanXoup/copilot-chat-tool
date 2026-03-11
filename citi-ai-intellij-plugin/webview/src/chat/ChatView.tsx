import { useEffect } from 'react'
import { useChat } from './useChat'
import Toolbar from './Toolbar'
import MessageList from './MessageList'
import InputArea from './InputArea'

interface ChatViewProps {
  loadSessionId?: string
  onSessionLoaded?: () => void
}

export default function ChatView({ loadSessionId, onSessionLoaded }: ChatViewProps) {
  const chat = useChat()

  useEffect(() => {
    if (loadSessionId) {
      chat.loadSession(loadSessionId)
      onSessionLoaded?.()
    }
  }, [loadSessionId]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <Toolbar
        modes={chat.modes}
        models={chat.models}
        selectedModeId={chat.selectedModeId}
        selectedModelId={chat.selectedModelId}
        onModeChange={chat.setSelectedModeId}
        onModelChange={chat.setSelectedModelId}
        newSession={chat.newSession}
        onNewSessionChange={chat.setNewSession}
        silent={chat.silent}
        onSilentChange={chat.setSilent}
        currentSessionId={chat.currentSessionId}
      />

      <MessageList
        messages={chat.messages}
        toolCalls={chat.toolCalls}
        steps={chat.steps}
        messagesEndRef={chat.messagesEndRef}
      />

      <InputArea
        input={chat.input}
        isLoading={chat.isLoading}
        onInputChange={chat.setInput}
        onSend={chat.handleSend}
        onStop={chat.handleStop}
        onKeyDown={chat.handleKeyDown}
      />
    </>
  )
}
