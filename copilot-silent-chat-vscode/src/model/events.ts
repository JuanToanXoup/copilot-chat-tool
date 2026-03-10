/**
 * SilentChatEvent — sealed type hierarchy matching the IntelliJ Kotlin sealed class.
 * Every event carries a timestamp (epoch millis).
 */

export interface ToolCallResult {
  type: string;
  value: string;
}

export interface Annotation {
  type: string;
  title?: string;
  description?: string;
  uri?: string;
  lineRange?: { start: number; end: number };
}

// --- Base ---

interface BaseEvent {
  timestamp: number;
}

// --- Lifecycle Events ---

export interface SessionReadyEvent extends BaseEvent {
  type: 'SessionReady';
  sessionId: string;
}

export interface BeginEvent extends BaseEvent {
  type: 'Begin';
}

export interface CompleteEvent extends BaseEvent {
  type: 'Complete';
}

export interface ErrorEvent extends BaseEvent {
  type: 'Error';
  message: string;
  code?: string;
}

export interface CancelEvent extends BaseEvent {
  type: 'Cancel';
}

// --- ID Sync Events ---

export interface ConversationIdSyncEvent extends BaseEvent {
  type: 'ConversationIdSync';
  conversationId: string;
}

export interface TurnIdSyncEvent extends BaseEvent {
  type: 'TurnIdSync';
  turnId: string;
}

// --- Streaming Events ---

export interface ReplyEvent extends BaseEvent {
  type: 'Reply';
  delta: string;
  accumulated: string;
  annotations?: Annotation[];
  parentTurnId?: string;
}

export interface StepsEvent extends BaseEvent {
  type: 'Steps';
  steps: StepInfo[];
}

export interface StepInfo {
  id: string;
  title: string;
  description?: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
}

// --- Tool Call Events ---

export interface ToolCallUpdateEvent extends BaseEvent {
  type: 'ToolCallUpdate';
  id: string;
  toolName: string;
  toolType?: string;
  status: 'running' | 'completed' | 'failed';
  input?: string;
  inputMessage?: string;
  output?: string;
  error?: string;
  progressMessage?: string;
  result?: ToolCallResult[];
  durationMs?: number;
  roundId?: string;
}

// --- Metadata Events ---

export interface SuggestedTitleEvent extends BaseEvent {
  type: 'SuggestedTitle';
  title: string;
}

// --- Union Type ---

export type SilentChatEvent =
  | SessionReadyEvent
  | BeginEvent
  | CompleteEvent
  | ErrorEvent
  | CancelEvent
  | ConversationIdSyncEvent
  | TurnIdSyncEvent
  | ReplyEvent
  | StepsEvent
  | ToolCallUpdateEvent
  | SuggestedTitleEvent;
