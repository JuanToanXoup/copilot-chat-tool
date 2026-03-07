/** C4 architecture file types — shared between ArchitectureView and ExplorerView */

export type C4Node = {
  id: string
  label: string
  description: string
  technology?: string
  type: 'system' | 'container' | 'component' | 'code'
  childFile?: string // relative path to child .c4.json
  [key: string]: unknown
}

export type C4Edge = {
  source: string
  target: string
  label: string
  technology?: string
  [key: string]: unknown
}

export type C4Level = {
  id: string
  title: string
  level: 1 | 2 | 3 | 4
  parentNodeId?: string
  parentFile?: string // relative path back to parent .c4.json
  nodes: C4Node[]
  edges: C4Edge[]
}

/** Summary returned by listArchitectureFiles */
export type ArchitectureFileSummary = {
  fileName: string
  filePath: string
  title: string
  level: number
  nodeCount: number
}
