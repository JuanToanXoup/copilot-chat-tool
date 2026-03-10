import * as vscode from 'vscode';
import { SilentChatEvent, StepInfo, ToolCallResult } from '../model/events';

/**
 * ProgressHandler — equivalent of SilentProgressHandler.kt
 *
 * Emits SilentChatEvent instances through a shared EventEmitter.
 * Each handler is bound to a single session/conversation.
 */
export class ProgressHandler {
  private toolCallStartTimes = new Map<string, number>();
  private lastToolCallStatus = new Map<string, string>();

  constructor(
    private readonly sessionId: string,
    private readonly emitter: vscode.EventEmitter<[string, SilentChatEvent]>
  ) {}

  private emit(event: SilentChatEvent): void {
    this.emitter.fire([this.sessionId, event]);
  }

  emitSessionReady(sessionId: string): void {
    this.emit({ type: 'SessionReady', sessionId, timestamp: Date.now() });
  }

  emitBegin(): void {
    this.emit({ type: 'Begin', timestamp: Date.now() });
  }

  emitConversationIdSync(conversationId: string): void {
    this.emit({ type: 'ConversationIdSync', conversationId, timestamp: Date.now() });
  }

  emitTurnIdSync(turnId: string): void {
    this.emit({ type: 'TurnIdSync', turnId, timestamp: Date.now() });
  }

  emitReply(delta: string, accumulated: string, annotations?: any[], parentTurnId?: string): void {
    this.emit({
      type: 'Reply',
      delta,
      accumulated,
      annotations,
      parentTurnId,
      timestamp: Date.now(),
    });
  }

  emitSteps(steps: StepInfo[]): void {
    this.emit({ type: 'Steps', steps, timestamp: Date.now() });
  }

  emitToolCallUpdate(params: {
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
    roundId?: string;
  }): void {
    const { id, status } = params;

    // Skip duplicate status emissions (same pattern as Kotlin)
    const lastStatus = this.lastToolCallStatus.get(id);
    if (lastStatus === status) {
      return;
    }
    this.lastToolCallStatus.set(id, status);

    // Track start time on first appearance
    if (!this.toolCallStartTimes.has(id)) {
      this.toolCallStartTimes.set(id, Date.now());
    }

    // Calculate duration on terminal state
    let durationMs: number | undefined;
    if (status === 'completed' || status === 'failed') {
      const startTime = this.toolCallStartTimes.get(id);
      if (startTime) {
        durationMs = Date.now() - startTime;
      }
      // Clean up tracking
      this.toolCallStartTimes.delete(id);
      this.lastToolCallStatus.delete(id);
    }

    this.emit({
      type: 'ToolCallUpdate',
      ...params,
      durationMs,
      timestamp: Date.now(),
    });
  }

  emitSuggestedTitle(title: string): void {
    this.emit({ type: 'SuggestedTitle', title, timestamp: Date.now() });
  }

  emitComplete(): void {
    this.emit({ type: 'Complete', timestamp: Date.now() });
  }

  emitError(error: unknown): void {
    const message = error instanceof Error ? error.message : String(error);
    this.emit({ type: 'Error', message, timestamp: Date.now() });
  }

  emitCancel(): void {
    this.emit({ type: 'Cancel', timestamp: Date.now() });
  }
}
