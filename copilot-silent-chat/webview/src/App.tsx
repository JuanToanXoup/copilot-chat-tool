import { useState, useCallback, useEffect } from 'react'
import ChatView from './chat/ChatView'
import SessionsView from './SessionsView'
import LogsView from './LogsView'
import ExplorerView from './explorer/ExplorerView'
import PlaybookView from './playbook/PlaybookView'
import { subscribe, type JcefDataEvent } from './bridge'
import './style.css'

type Tab = 'chat' | 'sessions' | 'logs' | 'explorer' | 'playbooks'

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>('chat')
  const [loadSessionId, setLoadSessionId] = useState<string | undefined>()

  // When a node-detail push arrives from a file editor, switch to Explorer tab
  useEffect(() => {
    return subscribe((data: JcefDataEvent) => {
      if (data.channel === 'node-detail') {
        setActiveTab('explorer')
      }
    })
  }, [])

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
        <button className={`tab ${activeTab === 'explorer' ? 'tab-active' : ''}`} onClick={() => setActiveTab('explorer')}>Explorer</button>
        <button className={`tab ${activeTab === 'playbooks' ? 'tab-active' : ''}`} onClick={() => setActiveTab('playbooks')}>Playbooks</button>
      </div>

      {activeTab === 'chat' && <ChatView loadSessionId={loadSessionId} onSessionLoaded={handleSessionLoaded} />}
      {activeTab === 'sessions' && <SessionsView onOpenSession={handleOpenSession} />}
      {activeTab === 'logs' && <LogsView />}
      {activeTab === 'explorer' && <ExplorerView onOpenSession={handleOpenSession} />}
      {activeTab === 'playbooks' && <PlaybookView />}
    </div>
  )
}
