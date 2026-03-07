import { useState, useEffect, useCallback } from 'react'
import {
  ReactFlow,
  Controls,
  Background,
  BackgroundVariant,
  useNodesState,
  useEdgesState,
  Handle,
  Position,
  BaseEdge,
  getSmoothStepPath,
  type Node,
  type Edge,
  type EdgeProps,
  type NodeMouseHandler,
  MarkerType,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { subscribe, postMessage, type JcefDataEvent } from '../bridge'
import type { Playbook, PlaybookStep, PlaybookParam, PlaybookProgress, StepState } from './types'
import './playbook.css'
import '../explorer/explorer.css'

/* ─── Constants ─── */

const NODE_W = 260
const NODE_H = 76
const CONNECTOR_SIZE = 28
const H_GAP = 40
const V_GAP_STEP = 50
const V_GAP_CONNECTOR = 50

const stateColors: Record<StepState, { accent: string; bg: string }> = {
  idle:    { accent: '#6272a4', bg: 'rgba(98,114,164,0.06)' },
  running: { accent: '#f1fa8c', bg: 'rgba(241,250,140,0.08)' },
  success: { accent: '#50fa7b', bg: 'rgba(80,250,123,0.08)' },
  error:   { accent: '#ff5555', bg: 'rgba(255,85,85,0.08)' },
}

/* ─── Custom tree layout ─── */

type LayoutResult = { nodes: Node[]; edges: Edge[] }

function layoutPlaybook(playbook: Playbook): LayoutResult {
  playbook.steps.forEach((s) => { if (!s.dependsOn) s.dependsOn = [] })

  const steps = [...playbook.steps]
  if (playbook.synthesisPrompt) {
    const depTargets = new Set(steps.flatMap((s) => s.dependsOn))
    const leafIds = steps.filter((s) => !depTargets.has(s.id) || s.dependsOn.length === 0)
      .map((s) => s.id)
    if (leafIds.length > 0) {
      steps.push({
        id: '__synthesis__',
        name: 'Synthesis',
        prompt: playbook.synthesisPrompt,
        agentMode: false,
        dependsOn: leafIds,
      })
    }
  }

  const stepMap = new Map<string, PlaybookStep>()
  steps.forEach((s) => stepMap.set(s.id, s))

  const layerOf = new Map<string, number>()
  function depth(id: string): number {
    if (layerOf.has(id)) return layerOf.get(id)!
    const step = stepMap.get(id)
    if (!step || step.dependsOn.length === 0) {
      layerOf.set(id, 0)
      return 0
    }
    const d = 1 + Math.max(...step.dependsOn.map(depth))
    layerOf.set(id, d)
    return d
  }
  steps.forEach((s) => depth(s.id))

  const maxLayer = Math.max(...Array.from(layerOf.values()), 0)
  const layers: PlaybookStep[][] = Array.from({ length: maxLayer + 1 }, () => [])
  steps.forEach((s) => {
    layers[layerOf.get(s.id) ?? 0].push(s)
  })

  const childrenOf = new Map<string, string[]>()
  steps.forEach((s) => {
    s.dependsOn.forEach((depId) => {
      if (!childrenOf.has(depId)) childrenOf.set(depId, [])
      childrenOf.get(depId)!.push(s.id)
    })
  })

  const nodes: Node[] = []
  const edges: Edge[] = []
  let connectorId = 0
  const posOf = new Map<string, { x: number; y: number }>()
  let currentY = 0

  for (let layer = 0; layer <= maxLayer; layer++) {
    const group = layers[layer]
    if (group.length === 0) continue
    const isParallel = group.length > 1
    const totalW = group.length * NODE_W + (group.length - 1) * H_GAP
    const startX = -totalW / 2

    if (isParallel && layer > 0) {
      const parentIds = new Set<string>()
      group.forEach((s) => s.dependsOn.forEach((d) => parentIds.add(d)))

      parentIds.forEach((parentId) => {
        const parentPos = posOf.get(parentId)
        if (!parentPos) return
        const forkId = `fork-${connectorId++}`
        const forkY = currentY
        const forkX = parentPos.x + NODE_W / 2 - CONNECTOR_SIZE / 2
        nodes.push({
          id: forkId, type: 'connector',
          position: { x: forkX, y: forkY },
          data: { label: '' },
          style: { width: CONNECTOR_SIZE, height: CONNECTOR_SIZE },
        })
        edges.push({
          id: `e-${parentId}-${forkId}`, source: parentId, target: forkId,
          sourceHandle: 'bottom-src', targetHandle: 'top-tgt', type: 'pbEdge',
          markerEnd: { type: MarkerType.ArrowClosed, color: '#6272a4', width: 12, height: 12 },
        })
        posOf.set(forkId, { x: forkX, y: forkY })
      })
      currentY += CONNECTOR_SIZE + V_GAP_CONNECTOR
    }

    const stepY = currentY
    group.forEach((step, i) => {
      const x = startX + i * (NODE_W + H_GAP)
      nodes.push({
        id: step.id, type: 'pbStep',
        position: { x, y: stepY },
        data: step,
        style: { width: NODE_W },
      })
      posOf.set(step.id, { x, y: stepY })
    })
    currentY += NODE_H + V_GAP_STEP

    if (isParallel && layer > 0) {
      group.forEach((step) => {
        step.dependsOn.forEach((depId) => {
          const forkNode = nodes.find(
            (n) => n.type === 'connector' && n.id.startsWith('fork-') &&
            edges.some((e) => e.source === depId && e.target === n.id)
          )
          if (forkNode) {
            edges.push({
              id: `e-${forkNode.id}-${step.id}`, source: forkNode.id, target: step.id,
              sourceHandle: 'bottom-src', targetHandle: 'top-tgt', type: 'pbEdge',
              markerEnd: { type: MarkerType.ArrowClosed, color: '#6272a4', width: 12, height: 12 },
            })
          }
        })
      })
    }

    const nextLayer = layers[layer + 1]
    if (isParallel && nextLayer && nextLayer.length === 1) {
      const joinId = `join-${connectorId++}`
      const joinX = -CONNECTOR_SIZE / 2
      const joinY = currentY
      nodes.push({
        id: joinId, type: 'connector',
        position: { x: joinX, y: joinY },
        data: { label: '' },
        style: { width: CONNECTOR_SIZE, height: CONNECTOR_SIZE },
      })
      posOf.set(joinId, { x: joinX, y: joinY })
      group.forEach((step) => {
        edges.push({
          id: `e-${step.id}-${joinId}`, source: step.id, target: joinId,
          sourceHandle: 'bottom-src', targetHandle: 'top-tgt', type: 'pbEdge',
          markerEnd: { type: MarkerType.ArrowClosed, color: '#6272a4', width: 12, height: 12 },
        })
      })
      edges.push({
        id: `e-${joinId}-${nextLayer[0].id}`, source: joinId, target: nextLayer[0].id,
        sourceHandle: 'bottom-src', targetHandle: 'top-tgt', type: 'pbEdge',
        markerEnd: { type: MarkerType.ArrowClosed, color: '#6272a4', width: 12, height: 12 },
      })
      currentY += CONNECTOR_SIZE + V_GAP_CONNECTOR
    } else if (!isParallel && layer < maxLayer) {
      const step = group[0]
      const children = childrenOf.get(step.id) || []
      const nextGroup = layers[layer + 1]
      if (nextGroup && nextGroup.length === 1) {
        children.forEach((childId) => {
          if (!edges.some((e) => e.target === childId)) {
            edges.push({
              id: `e-${step.id}-${childId}`, source: step.id, target: childId,
              sourceHandle: 'bottom-src', targetHandle: 'top-tgt', type: 'pbEdge',
              markerEnd: { type: MarkerType.ArrowClosed, color: '#6272a4', width: 12, height: 12 },
            })
          }
        })
      }
    }
  }

  return { nodes, edges }
}

/* ─── Extract variable references from a prompt ─── */

function extractVarRefs(prompt: string): string[] {
  const refs: string[] = []
  const regex = /\{([\w_-]+)\}/g
  let match: RegExpExecArray | null
  while ((match = regex.exec(prompt)) !== null) {
    if (!refs.includes(match[1])) refs.push(match[1])
  }
  return refs
}

/* ─── Connector Node ─── */

function ConnectorNode() {
  return (
    <div className="pb-connector-node">
      <Handle id="top-tgt" type="target" position={Position.Top} className="c4-handle" />
      <Handle id="bottom-src" type="source" position={Position.Bottom} className="c4-handle" />
      <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
        <path d="M7 3v8M3 7h8" stroke="#6272a4" strokeWidth="1.5" strokeLinecap="round" />
      </svg>
    </div>
  )
}

/* ─── Step Node with state + variable indicators ─── */

function PbStepNode({ data }: { data: PlaybookStep & { _state?: StepState; _varRefs?: string[]; _resolvedVars?: string[] } }) {
  const state = data._state || 'idle'
  const { accent, bg } = stateColors[state]
  const varRefs = data._varRefs || extractVarRefs(data.prompt)
  const resolvedVars = data._resolvedVars || []
  const unresolvedCount = varRefs.filter((v) => !resolvedVars.includes(v)).length

  return (
    <div className="c4-step" style={{ borderColor: state !== 'idle' ? accent : undefined }}>
      <Handle id="top-src" type="source" position={Position.Top} className="c4-handle" />
      <Handle id="top-tgt" type="target" position={Position.Top} className="c4-handle" />
      <Handle id="bottom-src" type="source" position={Position.Bottom} className="c4-handle" />
      <Handle id="bottom-tgt" type="target" position={Position.Bottom} className="c4-handle" />

      <div className="c4-step-header" style={{ background: bg }}>
        <div className="c4-step-icon" style={{ background: `${accent}22`, color: accent }}>
          {state === 'running' ? (
            <div className="pb-spinner" />
          ) : state === 'success' ? (
            <svg width="12" height="12" viewBox="0 0 13 13" fill="none">
              <path d="M2.5 6.5L5.5 9.5L10.5 3.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          ) : state === 'error' ? (
            <svg width="12" height="12" viewBox="0 0 13 13" fill="none">
              <path d="M3 3l7 7M10 3l-7 7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
          ) : data.dependsOn.length === 0 ? (
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <path d="M6 1v10M1 6h10" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          ) : (
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <circle cx="6" cy="6" r="5" stroke="currentColor" strokeWidth="1.5" />
            </svg>
          )}
        </div>
        <div className="c4-step-info">
          <div className="c4-step-name">{data.name}</div>
          <div className="c4-step-cmd">
            {data.agentMode && <span className="pb-agent-badge" style={{ marginRight: 6 }}>Agent</span>}
            {varRefs.length > 0 && (
              <span style={{ color: unresolvedCount > 0 ? '#ffb86c' : '#50fa7b', fontSize: '0.65rem' }}>
                {unresolvedCount > 0
                  ? `${unresolvedCount} var${unresolvedCount > 1 ? 's' : ''} needed`
                  : `${varRefs.length} var${varRefs.length > 1 ? 's' : ''} resolved`}
              </span>
            )}
            {varRefs.length === 0 && (
              <span>{data.dependsOn.length > 0 ? `depends on: ${data.dependsOn.join(', ')}` : 'entry point'}</span>
            )}
          </div>
        </div>
        {state !== 'idle' && (
          <div style={{
            width: 8, height: 8, borderRadius: '50%', background: accent,
            flexShrink: 0, marginLeft: 'auto',
          }} />
        )}
      </div>
    </div>
  )
}

/* ─── Edge ─── */

function PbEdgeComponent({
  id, sourceX, sourceY, targetX, targetY,
  sourcePosition, targetPosition, markerEnd,
}: EdgeProps) {
  const [edgePath] = getSmoothStepPath({
    sourceX, sourceY, sourcePosition,
    targetX, targetY, targetPosition,
    borderRadius: 8,
  })
  return (
    <BaseEdge
      id={id}
      path={edgePath}
      markerEnd={markerEnd}
      style={{ stroke: '#6272a4', strokeWidth: 1.5 }}
    />
  )
}

const nodeTypes = { pbStep: PbStepNode, connector: ConnectorNode }
const edgeTypes = { pbEdge: PbEdgeComponent }

/* ─── DAG View ─── */

export default function PlaybookDagView() {
  const [playbook, setPlaybook] = useState<Playbook | null>(null)
  const [filePath, setFilePath] = useState<string | null>(null)
  const [nodes, setNodes, onNodesChange] = useNodesState([] as Node[])
  const [edges, setEdges, onEdgesChange] = useEdgesState([] as Edge[])
  const [paramValues, setParamValues] = useState<Record<string, string>>({})
  const [stepStates, setStepStates] = useState<Record<string, StepState>>({})
  const [stepResults, setStepResults] = useState<Record<string, string>>({})
  const [isRunning, setIsRunning] = useState(false)

  useEffect(() => {
    const unsub = subscribe((data: JcefDataEvent) => {
      if (data.channel === 'playbook-file') {
        const payload = data.payload as any
        // Editor wraps as { _filePath, _raw: <playbook> }
        const pb = (payload._raw || payload) as Playbook
        setPlaybook(pb)
        setFilePath(payload._filePath || null)
        // Init param values with defaults
        if (pb.parameters) {
          const init: Record<string, string> = {}
          for (const [key, param] of Object.entries(pb.parameters)) {
            init[key] = (param as PlaybookParam).default || ''
          }
          setParamValues(init)
        }
        const { nodes: n, edges: e } = layoutPlaybook(pb)
        setNodes(n)
        setEdges(e)
      }
      if (data.channel === 'playbook-progress') {
        const progress = data.payload as PlaybookProgress
        const states: Record<string, StepState> = {}
        const results: Record<string, string> = {}
        progress.steps.forEach((s) => {
          states[s.id] = s.state
          if (s.result) results[s.id] = s.result
        })
        setStepStates(states)
        setStepResults(results)
        setIsRunning(progress.steps.some((s) => s.state === 'running'))
      }
    })
    postMessage({ command: 'file-editor-ready' })
    return unsub
  }, [setNodes, setEdges])

  // Update node data with execution state and variable info
  useEffect(() => {
    if (!playbook) return
    const resolvedVarNames = Object.keys(stepResults).flatMap((id) => [`step_${id}_result`])
    // Also add 'results' if any steps completed
    if (Object.keys(stepResults).length > 0) resolvedVarNames.push('results')

    setNodes((prevNodes) =>
      prevNodes.map((node) => {
        if (node.type !== 'pbStep') return node
        const step = node.data as PlaybookStep
        return {
          ...node,
          data: {
            ...step,
            _state: stepStates[step.id] || 'idle',
            _varRefs: extractVarRefs(step.prompt),
            _resolvedVars: resolvedVarNames,
          },
        }
      })
    )
  }, [stepStates, stepResults, playbook, setNodes])

  const onNodeClick: NodeMouseHandler = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      if (node.type === 'connector') return
      const step = node.data as PlaybookStep
      const allSteps = playbook?.steps ?? []
      postMessage({
        command: 'showStepDetail',
        step,
        allSteps,
        playbookName: playbook?.name ?? '',
        stepStates,
        stepResults,
      })
    },
    [playbook, stepStates, stepResults],
  )

  const handleRun = useCallback(() => {
    if (!filePath && !playbook) return
    setIsRunning(true)
    const initial: Record<string, StepState> = {}
    playbook?.steps.forEach((s) => { initial[s.id] = 'idle' })
    setStepStates(initial)
    setStepResults({})
    postMessage({
      command: 'runPlaybook',
      path: filePath,
      params: paramValues,
    })
  }, [filePath, playbook, paramValues])

  if (!playbook) {
    return (
      <div className="ex-container" style={{ alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ color: '#6272a4', fontSize: 14 }}>Loading playbook...</div>
      </div>
    )
  }

  const paramEntries = playbook.parameters ? Object.entries(playbook.parameters) : []
  const hasParams = paramEntries.length > 0
  const allRequiredFilled = paramEntries
    .filter(([, p]) => p.required !== false)
    .every(([k]) => paramValues[k]?.trim())

  return (
    <div className="ex-container">
      <div className="c4-breadcrumb">
        <span className="c4-crumb-btn c4-crumb-active">{playbook.name}</span>
        <div className="c4-breadcrumb-right">
          <span className="c4-level-badge">{playbook.steps.length} steps</span>
          {!isRunning && (
            <button
              className="ex-analyze-btn"
              style={{ background: 'rgba(80,250,123,0.15)', color: '#50fa7b', borderColor: 'rgba(80,250,123,0.3)' }}
              disabled={hasParams && !allRequiredFilled}
              onClick={handleRun}
            >
              Run
            </button>
          )}
          {isRunning && (
            <span style={{ color: '#f1fa8c', fontSize: '0.75rem' }}>Running...</span>
          )}
        </div>
      </div>

      {/* Parameter inputs — always visible when playbook has parameters */}
      {hasParams && (
        <div className="pb-param-form">
          <div className="pb-param-title">Parameters</div>
          {paramEntries.map(([key, param]) => (
            <div key={key} className="pb-param-field">
              <label className="pb-param-label">
                {key}
                {param.required !== false && <span style={{ color: '#ff5555' }}> *</span>}
              </label>
              <div className="pb-param-desc">{param.description}</div>
              <input
                className="pb-param-input"
                type="text"
                value={paramValues[key] || ''}
                placeholder={param.default || ''}
                onChange={(e) => setParamValues((prev) => ({ ...prev, [key]: e.target.value }))}
              />
            </div>
          ))}
        </div>
      )}

      <div className="ex-graph" style={{ flex: 1 }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onNodeClick={onNodeClick}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          fitView
          fitViewOptions={{ padding: 0.3 }}
          minZoom={0.3}
          maxZoom={2}
          proOptions={{ hideAttribution: true }}
        >
          <Controls showInteractive={false} className="ex-controls" />
          <Background variant={BackgroundVariant.Dots} gap={24} size={1} color="#44475a" />
        </ReactFlow>
      </div>
    </div>
  )
}
