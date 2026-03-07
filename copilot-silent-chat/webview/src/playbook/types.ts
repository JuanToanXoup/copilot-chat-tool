/** Playbook JSON schema — matches .citi-ai/playbooks/*.json */

export type PlaybookParam = {
  type: string
  description: string
  required?: boolean
  default?: string
}

export type PlaybookStep = {
  id: string
  name: string
  prompt: string
  agentMode: boolean
  dependsOn: string[]
}

export type Playbook = {
  id: string
  name: string
  description: string
  parameters: Record<string, PlaybookParam>
  steps: PlaybookStep[]
  synthesisPrompt: string
  stepTimeoutMs: number
  timeoutMs: number
}

export type StepState = 'idle' | 'running' | 'success' | 'error'

export type PlaybookFileSummary = {
  fileName: string
  filePath: string
  name: string
  stepCount: number
}
