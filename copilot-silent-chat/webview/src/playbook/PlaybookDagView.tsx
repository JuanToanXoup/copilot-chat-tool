import { useState, useEffect } from 'react'
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
  MarkerType,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { subscribe, postMessage, type JcefDataEvent } from '../bridge'
import type { Playbook, PlaybookStep } from './types'
import './playbook.css'
import '../explorer/explorer.css'

/* ─── Constants ─── */

const NODE_W = 260
const NODE_H = 76
const CONNECTOR_SIZE = 28
const H_GAP = 40
const V_GAP_STEP = 50       // between step and connector
const V_GAP_CONNECTOR = 50  // between connector and next step

/* ─── Custom tree layout ─── */

type LayoutResult = { nodes: Node[]; edges: Edge[] }

function layoutPlaybook(playbook: Playbook): LayoutResult {
  // Normalize: ensure every step has a dependsOn array
  playbook.steps.forEach((s) => { if (!s.dependsOn) s.dependsOn = [] })

  // Add implicit synthesis step if synthesisPrompt exists
  const steps = [...playbook.steps]
  if (playbook.synthesisPrompt) {
    // Find leaf steps (not depended on by any other step)
    const depTargets = new Set(steps.flatMap((s) => s.dependsOn))
    const leafIds = steps.filter((s) => !depTargets.has(s.id) || s.dependsOn.length === 0)
      .map((s) => s.id)
    // Only add if there are steps to synthesize
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

  // Assign layers via longest-path (ensures parallel siblings share a layer)
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

  // Group steps by layer
  const maxLayer = Math.max(...Array.from(layerOf.values()), 0)
  const layers: PlaybookStep[][] = Array.from({ length: maxLayer + 1 }, () => [])
  steps.forEach((s) => {
    layers[layerOf.get(s.id) ?? 0].push(s)
  })

  // Build children map: parent → children in the next layer
  const childrenOf = new Map<string, string[]>()
  steps.forEach((s) => {
    s.dependsOn.forEach((depId) => {
      if (!childrenOf.has(depId)) childrenOf.set(depId, [])
      childrenOf.get(depId)!.push(s.id)
    })
  })

  // Build parents map: child → parents
  const parentsOf = new Map<string, string[]>()
  steps.forEach((s) => {
    parentsOf.set(s.id, [...s.dependsOn])
  })

  const nodes: Node[] = []
  const edges: Edge[] = []
  let connectorId = 0

  // Position tracking
  const posOf = new Map<string, { x: number; y: number }>()
  let currentY = 0

  for (let layer = 0; layer <= maxLayer; layer++) {
    const group = layers[layer]
    if (group.length === 0) continue

    const isParallel = group.length > 1

    // Calculate total width of this layer
    const totalW = group.length * NODE_W + (group.length - 1) * H_GAP
    const startX = -totalW / 2

    // Determine if we need a fork connector above this layer
    // (previous layer has a single node that fans out to multiple here)
    if (isParallel && layer > 0) {
      // Find common parents
      const parentIds = new Set<string>()
      group.forEach((s) => s.dependsOn.forEach((d) => parentIds.add(d)))

      parentIds.forEach((parentId) => {
        const parentPos = posOf.get(parentId)
        if (!parentPos) return

        // Fork connector below parent
        const forkId = `fork-${connectorId++}`
        const forkY = currentY
        const forkX = parentPos.x + NODE_W / 2 - CONNECTOR_SIZE / 2

        nodes.push({
          id: forkId,
          type: 'connector',
          position: { x: forkX, y: forkY },
          data: { label: '' },
          style: { width: CONNECTOR_SIZE, height: CONNECTOR_SIZE },
        })

        // Edge: parent → fork
        edges.push({
          id: `e-${parentId}-${forkId}`,
          source: parentId,
          target: forkId,
          sourceHandle: 'bottom-src',
          targetHandle: 'top-tgt',
          type: 'pbEdge',
          markerEnd: { type: MarkerType.ArrowClosed, color: '#6272a4', width: 12, height: 12 },
        })

        posOf.set(forkId, { x: forkX, y: forkY })
      })

      currentY += CONNECTOR_SIZE + V_GAP_CONNECTOR
    }

    // Place step nodes
    const stepY = currentY
    group.forEach((step, i) => {
      const x = startX + i * (NODE_W + H_GAP)
      nodes.push({
        id: step.id,
        type: 'pbStep',
        position: { x, y: stepY },
        data: step,
        style: { width: NODE_W },
      })
      posOf.set(step.id, { x, y: stepY })
    })

    currentY += NODE_H + V_GAP_STEP

    // If parallel, add edges from fork connectors to each step
    if (isParallel && layer > 0) {
      group.forEach((step) => {
        step.dependsOn.forEach((depId) => {
          // Find the fork connector for this parent
          const forkNode = nodes.find(
            (n) => n.type === 'connector' && n.id.startsWith('fork-') &&
            edges.some((e) => e.source === depId && e.target === n.id)
          )
          if (forkNode) {
            edges.push({
              id: `e-${forkNode.id}-${step.id}`,
              source: forkNode.id,
              target: step.id,
              sourceHandle: 'bottom-src',
              targetHandle: 'top-tgt',
              type: 'pbEdge',
              markerEnd: { type: MarkerType.ArrowClosed, color: '#6272a4', width: 12, height: 12 },
            })
          }
        })
      })
    }

    // Check if next layer is a single join node — add join connector
    const nextLayer = layers[layer + 1]
    if (isParallel && nextLayer && nextLayer.length === 1) {
      const joinId = `join-${connectorId++}`
      const joinX = -CONNECTOR_SIZE / 2
      const joinY = currentY

      nodes.push({
        id: joinId,
        type: 'connector',
        position: { x: joinX, y: joinY },
        data: { label: '' },
        style: { width: CONNECTOR_SIZE, height: CONNECTOR_SIZE },
      })
      posOf.set(joinId, { x: joinX, y: joinY })

      // Edges: each parallel step → join
      group.forEach((step) => {
        edges.push({
          id: `e-${step.id}-${joinId}`,
          source: step.id,
          target: joinId,
          sourceHandle: 'bottom-src',
          targetHandle: 'top-tgt',
          type: 'pbEdge',
          markerEnd: { type: MarkerType.ArrowClosed, color: '#6272a4', width: 12, height: 12 },
        })
      })

      // Edge: join → next step (will be placed in next iteration)
      edges.push({
        id: `e-${joinId}-${nextLayer[0].id}`,
        source: joinId,
        target: nextLayer[0].id,
        sourceHandle: 'bottom-src',
        targetHandle: 'top-tgt',
        type: 'pbEdge',
        markerEnd: { type: MarkerType.ArrowClosed, color: '#6272a4', width: 12, height: 12 },
      })

      currentY += CONNECTOR_SIZE + V_GAP_CONNECTOR
    } else if (!isParallel && layer < maxLayer) {
      // Serial: direct edge from this step to its children
      const step = group[0]
      const children = childrenOf.get(step.id) || []
      const nextGroup = layers[layer + 1]
      // Only add direct edges if next layer is NOT parallel (parallel gets fork/join)
      if (nextGroup && nextGroup.length === 1) {
        children.forEach((childId) => {
          // Only add if not already covered by a join connector
          if (!edges.some((e) => e.target === childId)) {
            edges.push({
              id: `e-${step.id}-${childId}`,
              source: step.id,
              target: childId,
              sourceHandle: 'bottom-src',
              targetHandle: 'top-tgt',
              type: 'pbEdge',
              markerEnd: { type: MarkerType.ArrowClosed, color: '#6272a4', width: 12, height: 12 },
            })
          }
        })
      }
    }
  }

  return { nodes, edges }
}

/* ─── Connector Node (fork/join circle with +) ─── */

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

/* ─── Step Node ─── */

function PbStepNode({ data }: { data: PlaybookStep }) {
  return (
    <div className="c4-step">
      <Handle id="top-src" type="source" position={Position.Top} className="c4-handle" />
      <Handle id="top-tgt" type="target" position={Position.Top} className="c4-handle" />
      <Handle id="bottom-src" type="source" position={Position.Bottom} className="c4-handle" />
      <Handle id="bottom-tgt" type="target" position={Position.Bottom} className="c4-handle" />

      <div className="c4-step-header" style={{ background: 'rgba(189, 147, 249, 0.06)' }}>
        <div className="c4-step-icon" style={{ background: 'rgba(189, 147, 249, 0.15)', color: '#bd93f9' }}>
          {data.dependsOn.length === 0 ? (
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
            {data.dependsOn.length > 0
              ? `depends on: ${data.dependsOn.join(', ')}`
              : 'entry point'}
          </div>
        </div>
      </div>
    </div>
  )
}

/* ─── Edge (smooth step for right-angle connectors) ─── */

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
  const [nodes, setNodes, onNodesChange] = useNodesState([] as Node[])
  const [edges, setEdges, onEdgesChange] = useEdgesState([] as Edge[])

  useEffect(() => {
    const unsub = subscribe((data: JcefDataEvent) => {
      if (data.channel === 'playbook-file') {
        const pb = data.payload as Playbook
        setPlaybook(pb)
        const { nodes: n, edges: e } = layoutPlaybook(pb)
        setNodes(n)
        setEdges(e)
      }
    })
    postMessage({ command: 'file-editor-ready' })
    return unsub
  }, [setNodes, setEdges])

  if (!playbook) {
    return (
      <div className="ex-container" style={{ alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ color: '#6272a4', fontSize: 14 }}>Loading playbook...</div>
      </div>
    )
  }

  return (
    <div className="ex-container">
      <div className="c4-breadcrumb">
        <span className="c4-crumb-btn c4-crumb-active">{playbook.name}</span>
        <div className="c4-breadcrumb-right">
          <span className="c4-level-badge">{playbook.steps.length} steps</span>
        </div>
      </div>

      <div className="ex-graph" style={{ flex: 1 }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
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
