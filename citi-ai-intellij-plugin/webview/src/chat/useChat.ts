import { useState, useEffect, useRef, useCallback } from 'react'
import { subscribe, sendMessage, stopGeneration, requestModels, requestModes, requestSession, type JcefDataEvent } from '../bridge'
import type { ChatMessage, ToolCall, StepInfo, ModelOption, ModeOption } from '../types'

let messageIdCounter = 0
function nextId(): string {
  return `msg-${++messageIdCounter}-${Date.now()}`
}

export function useChat() {
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
        case 'session':
          hydrateSession(payload)
          break
      }
    })

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

  function hydrateSession(payload: any) {
    const sessionId = payload.sessionId as string
    const entries = payload.entries as any[]

    const msgs: ChatMessage[] = []
    const tcs: ToolCall[] = []
    const stps: StepInfo[] = []

    for (const e of entries) {
      switch (e.entryType) {
        case 'message': {
          if (e.prompt) {
            msgs.push({
              id: `${e.id}-user`,
              role: 'user',
              content: e.prompt,
              timestamp: e.startTime,
              sessionId,
            })
          }
          if (e.response) {
            msgs.push({
              id: `${e.id}-assistant`,
              role: 'assistant',
              content: e.response,
              timestamp: e.endTime ?? e.startTime,
              sessionId,
            })
          }
          break
        }
        case 'tool_call': {
          let input: Record<string, unknown> | undefined
          try { input = e.input ? JSON.parse(e.input) : undefined } catch { input = undefined }

          let result: Array<{ type?: string; value?: unknown }> | undefined
          try { result = e.output ? JSON.parse(e.output) : undefined } catch { result = undefined }

          tcs.push({
            toolCallId: e.id,
            toolName: e.toolName ?? 'unknown',
            toolType: e.toolType,
            input,
            inputMessage: e.inputMessage,
            status: e.status ?? 'completed',
            result,
            error: e.error,
            durationMs: e.durationMs,
            sessionId,
            turnId: e.turnId,
          })
          break
        }
        case 'step': {
          stps.push({
            id: e.id,
            title: e.title ?? '',
            description: e.description,
            status: e.status ?? 'completed',
          })
          break
        }
      }
    }

    setMessages(msgs)
    setToolCalls(tcs)
    setSteps(stps)
    setCurrentSessionId(sessionId)
    setNewSession(false)
    setIsLoading(false)
    currentAssistantId.current = null
  }

  function loadSession(sessionId: string) {
    requestSession(sessionId)
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

  return {
    messages, toolCalls, steps,
    models, modes, selectedModelId, setSelectedModelId, selectedModeId, setSelectedModeId,
    currentSessionId, isLoading, input, setInput,
    newSession, setNewSession, silent, setSilent,
    messagesEndRef,
    handleSend, handleStop, handleKeyDown, loadSession,
  }
}
