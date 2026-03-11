import { useState, useCallback, useEffect } from 'react'
import {
  ReactFlow,
  Controls,
  MiniMap,
  Background,
  BackgroundVariant,
  useNodesState,
  useEdgesState,
  Handle,
  Position,
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  type Node,
  type Edge,
  type NodeMouseHandler,
  type EdgeProps,
  MarkerType,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import '../explorer/explorer.css'
import { subscribe, postMessage, type JcefDataEvent } from '../bridge'
import type { C4Node, C4Edge, C4Level } from '../explorer/types'
import ELK, { type ElkNode } from 'elkjs/lib/elk.bundled.js'

/* ─── Node Type Config ─── */

const typeConfig: Record<string, { accent: string; bgTint: string; label: string; letter: string }> = {
  system:    { accent: '#8be9fd', bgTint: 'rgba(139,233,253,0.08)', label: 'System',    letter: 'S' },
  container: { accent: '#50fa7b', bgTint: 'rgba(80,250,123,0.08)',  label: 'Container', letter: 'C' },
  component: { accent: '#ffb86c', bgTint: 'rgba(255,184,108,0.08)', label: 'Component', letter: 'K' },
  code:      { accent: '#bd93f9', bgTint: 'rgba(189,147,249,0.08)', label: 'Code',      letter: '{}' },
}

/* ─── Layout: ELK (layered algorithm) ─── */

const elk = new ELK()

async function layoutLevel(level: C4Level): Promise<{ nodes: Node[]; edges: Edge[] }> {
  const nodeW = level.level === 1 ? 300 : 260
  const nodeH = level.level === 1 ? 160 : 140

  const graph: ElkNode = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'DOWN',
      'elk.spacing.nodeNode': '80',
      'elk.layered.spacing.nodeNodeBetweenLayers': '100',
      'elk.layered.spacing.edgeEdgeBetweenLayers': '30',
      'elk.layered.spacing.edgeNodeBetweenLayers': '40',
      'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
      'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      'elk.padding': '[top=20,left=20,bottom=20,right=20]',
    },
    children: level.nodes.map((n) => ({
      id: n.id,
      width: nodeW,
      height: nodeH,
    })),
    edges: level.edges.map((e, i) => ({
      id: `e-${i}`,
      sources: [e.source],
      targets: [e.target],
    })),
  }

  const laid = await elk.layout(graph)

  // Build a position lookup for computing best edge connection sides
  const posMap = new Map<string, { x: number; y: number }>()
  for (const c of laid.children ?? []) {
    posMap.set(c.id, { x: (c.x ?? 0) + nodeW / 2, y: (c.y ?? 0) + nodeH / 2 })
  }

  const nodes: Node[] = level.nodes.map((n) => {
    const elkNode = laid.children?.find((c) => c.id === n.id)
    return {
      id: n.id,
      type: 'c4',
      position: { x: elkNode?.x ?? 0, y: elkNode?.y ?? 0 },
      data: n,
      style: { width: nodeW },
    }
  })

  const edges: Edge[] = level.edges.map((e, i) => {
    const src = posMap.get(e.source)
    const tgt = posMap.get(e.target)
    let sourceSide = 'bottom'
    let targetSide = 'top'

    if (src && tgt) {
      const dx = tgt.x - src.x
      const dy = tgt.y - src.y
      if (Math.abs(dx) > Math.abs(dy)) {
        sourceSide = dx > 0 ? 'right' : 'left'
        targetSide = dx > 0 ? 'left' : 'right'
      } else {
        sourceSide = dy > 0 ? 'bottom' : 'top'
        targetSide = dy > 0 ? 'top' : 'bottom'
      }
    }

    const sourceHandle = `${sourceSide}-src`
    const targetHandle = `${targetSide}-tgt`

    return {
      id: `e-${i}-${e.source}-${e.target}`,
      source: e.source,
      target: e.target,
      sourceHandle,
      targetHandle,
      type: 'c4edge',
      data: e,
      markerEnd: { type: MarkerType.ArrowClosed, color: '#6272a4', width: 16, height: 16 },
    }
  })

  return { nodes, edges }
}

/* ─── Custom C4 Node (dashboard-style card) ─── */

function C4NodeComponent({ data }: { data: C4Node }) {
  const cfg = typeConfig[data.type] || typeConfig.system

  return (
    <div className="c4-step">
      <Handle id="top-src" type="source" position={Position.Top} className="c4-handle" />
      <Handle id="top-tgt" type="target" position={Position.Top} className="c4-handle" />
      <Handle id="right-src" type="source" position={Position.Right} className="c4-handle" />
      <Handle id="right-tgt" type="target" position={Position.Right} className="c4-handle" />
      <Handle id="bottom-src" type="source" position={Position.Bottom} className="c4-handle" />
      <Handle id="bottom-tgt" type="target" position={Position.Bottom} className="c4-handle" />
      <Handle id="left-src" type="source" position={Position.Left} className="c4-handle" />
      <Handle id="left-tgt" type="target" position={Position.Left} className="c4-handle" />

      {/* Header row */}
      <div className="c4-step-header" style={{ background: cfg.bgTint }}>
        <div className="c4-step-icon" style={{ background: `${cfg.accent}22`, color: cfg.accent }}>
          {cfg.letter}
        </div>
        <div className="c4-step-info">
          <div className="c4-step-name">{data.label}</div>
          {data.technology && (
            <div className="c4-step-cmd">{data.technology}</div>
          )}
        </div>
        <div className="c4-step-right">
          <span className="c4-step-badge" style={{ background: `${cfg.accent}1a`, color: cfg.accent }}>
            {cfg.label}
          </span>
          {data.childFile && (
            <svg className="c4-step-chevron" width="15" height="15" viewBox="0 0 16 16" fill="none">
              <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          )}
        </div>
      </div>

      {/* Description */}
      {data.description && (
        <div className="c4-step-body">
          <div className="c4-step-desc">{data.description}</div>
        </div>
      )}
    </div>
  )
}

/* ─── Custom C4 Edge ─── */

function C4EdgeComponent({
  id, sourceX, sourceY, targetX, targetY,
  sourcePosition, targetPosition, data, markerEnd,
}: EdgeProps) {
  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX, sourceY, sourcePosition,
    targetX, targetY, targetPosition,
  })

  const edgeData = data as C4Edge | undefined

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        markerEnd={markerEnd}
        style={{ stroke: '#6272a4', strokeWidth: 1.5 }}
      />
      {edgeData?.label && (
        <EdgeLabelRenderer>
          <div
            className="c4-edge-label"
            style={{
              transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
            }}
          >
            <div className="c4-edge-label-text">{edgeData.label}</div>
            {edgeData.technology && (
              <div className="c4-edge-label-tech">[{edgeData.technology}]</div>
            )}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  )
}

/* ─── Node/Edge type registrations ─── */

const nodeTypes = { c4: C4NodeComponent }
const edgeTypes = { c4edge: C4EdgeComponent }

/* ─── Architecture View (rendered inside FileEditor) ─── */

export default function ArchitectureView() {
  const [level, setLevel] = useState<C4Level | null>(null)
  const [basePath, setBasePath] = useState<string>('')
  const [nodes, setNodes, onNodesChange] = useNodesState([] as Node[])
  const [edges, setEdges, onEdgesChange] = useEdgesState([] as Edge[])
  // Register listener FIRST, then request data.
  // Kotlin responds to 'file-editor-ready' with the c4-file push.
  // This guarantees the listener is registered before data arrives.
  useEffect(() => {
    const unsub = subscribe((data: JcefDataEvent) => {
      if (data.channel === 'c4-file') {
        const payload = data.payload as { level: C4Level; basePath: string }
        setLevel(payload.level)
        setBasePath(payload.basePath)
        layoutLevel(payload.level).then(({ nodes: n, edges: e }) => {
          setNodes(n)
          setEdges(e)
        })
      }
    })
    // Tell Kotlin we're mounted and listening — it will push file content
    postMessage({ command: 'file-editor-ready' })
    return unsub
  }, [setNodes, setEdges])

  const onNodeClick: NodeMouseHandler = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      const data = node.data as C4Node
      // Send to Kotlin → tool window displays the detail
      postMessage({
        command: 'showNodeDetail',
        node: data,
        edges: level?.edges.filter((e) => e.source === data.id || e.target === data.id) ?? [],
        allNodes: level?.nodes ?? [],
        basePath,
      })
    },
    [level, basePath],
  )

  const onNodeDoubleClick: NodeMouseHandler = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      const data = node.data as C4Node
      if (data.childFile) {
        postMessage({ command: 'openFile', path: basePath + '/' + data.childFile })
      }
    },
    [basePath],
  )

  const goToParent = () => {
    if (level?.parentFile) {
      postMessage({ command: 'openFile', path: basePath + '/' + level.parentFile })
    }
  }

  if (!level) {
    return (
      <div className="ex-container" style={{ alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ color: '#8b949e', fontSize: 14 }}>Loading architecture...</div>
      </div>
    )
  }

  return (
    <div className="ex-container">
      {/* Header */}
      <div className="c4-breadcrumb">
        {level.parentFile && (
          <>
            <button className="c4-crumb-btn" onClick={goToParent}>&larr; Parent</button>
            <span className="c4-crumb-sep">/</span>
          </>
        )}
        <span className="c4-crumb-btn c4-crumb-active">{level.title}</span>

        <div className="c4-breadcrumb-right">
          <span className="c4-level-badge">L{level.level}</span>
          <span className="c4-node-count">{level.nodes.length} nodes</span>
        </div>
      </div>

      {/* Graph takes full area */}
      <div className="ex-graph" style={{ flex: 1 }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onNodeClick={onNodeClick}
          onNodeDoubleClick={onNodeDoubleClick}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          fitView
          fitViewOptions={{ padding: 0.2 }}
          minZoom={0.4}
          maxZoom={2}
          proOptions={{ hideAttribution: true }}
        >
          <Controls showInteractive={false} className="ex-controls" />
          <MiniMap
            nodeColor={() => '#44475a'}
            maskColor="rgba(0, 0, 0, 0.7)"
            className="ex-minimap"
          />
          <Background variant={BackgroundVariant.Dots} gap={24} size={1} color="#44475a" />
        </ReactFlow>
      </div>
    </div>
  )
}
