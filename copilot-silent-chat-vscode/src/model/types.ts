/**
 * Session and persistence types — mirrors IntelliJ SessionModels.kt
 */

export interface ChatSession {
  sessionId: string;
  playbookId?: string;
  startTime: number;
  endTime?: number;
  status: SessionStatus;
  title?: string;
  entries: SessionEntry[];
}

export type SessionStatus = 'ACTIVE' | 'COMPLETED' | 'ERROR' | 'CANCELLED';

export interface SessionEntry {
  id: string;
  chatSessionId: string;
  entryType: 'message' | 'tool_call' | 'step';
  turnId?: string;
  roundId?: string;
  startTime: number;
  endTime?: number;
  status?: string;
  durationMs?: number;

  // Message fields
  prompt?: string;
  response?: string;
  replyLength?: number;

  // Tool call fields
  toolName?: string;
  toolType?: string;
  input?: string;
  inputMessage?: string;
  output?: string;
  error?: string;
  progressMessage?: string;

  // Step fields
  title?: string;
  description?: string;
}

export interface PlaybookRun {
  id: string;
  startTime: number;
  endTime?: number;
  sessions: ChatSession[];
}

export interface ModelInfo {
  id: string;
  name: string;
  vendor?: string;
  family?: string;
}

export interface ModeInfo {
  id: string;
  name: string;
  description?: string;
}

export interface SendMessageParams {
  message: string;
  sessionId?: string;
  model?: string;
  mode?: string;
  newSession?: boolean;
  silent?: boolean;
}
