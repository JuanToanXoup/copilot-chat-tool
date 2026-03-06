import { useState, useCallback } from 'react'
import ChatView from './chat/ChatView'
import SessionsView from './SessionsView'
import LogsView from './LogsView'
import './style.css'

type Tab = 'chat' | 'sessions' | 'logs'

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>('chat')
  const [loadSessionId, setLoadSessionId] = useState<string | undefined>()

  const handleOpenSession = useCallback((sessionId: string) => {
    setLoadSessionId(sessionId)
    setActiveTab('chat')
  }, [])

  const handleSessionLoaded = useCallback(() => {
    setLoadSessionId(undefined)
  }, [])

  return (
    <div className="app">
      <div className="tab-bar">
        <button className={`tab ${activeTab === 'chat' ? 'tab-active' : ''}`} onClick={() => setActiveTab('chat')}>Chat</button>
        <button className={`tab ${activeTab === 'sessions' ? 'tab-active' : ''}`} onClick={() => setActiveTab('sessions')}>Sessions</button>
        <button className={`tab ${activeTab === 'logs' ? 'tab-active' : ''}`} onClick={() => setActiveTab('logs')}>Logs</button>
      </div>

      {activeTab === 'chat' && <ChatView loadSessionId={loadSessionId} onSessionLoaded={handleSessionLoaded} />}
      {activeTab === 'sessions' && <SessionsView onOpenSession={handleOpenSession} />}
      {activeTab === 'logs' && <LogsView />}
    </div>
  )
}
