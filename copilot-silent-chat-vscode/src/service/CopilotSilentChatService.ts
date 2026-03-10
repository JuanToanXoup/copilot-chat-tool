import * as vscode from 'vscode';
import { SilentChatEvent } from '../model/events';
import { SendMessageParams } from '../model/types';
import { ProgressHandler } from './ProgressHandler';
import { ModelService } from './ModelService';
import { randomUUID } from 'crypto';

/**
 * CopilotSilentChatService — equivalent of CopilotSilentChatService.kt
 *
 * Wraps the VS Code Language Model API to provide programmatic chat access
 * with streaming events, session management, and cancellation.
 *
 * IntelliJ version calls CopilotAgentSessionManager.sendMessage() directly.
 * VS Code version uses vscode.lm.selectChatModels() + model.sendRequest().
 */
export class CopilotSilentChatService implements vscode.Disposable {
  private _onEvent = new vscode.EventEmitter<[string, SilentChatEvent]>();
  readonly onEvent = this._onEvent.event;

  private _onStatusChanged = new vscode.EventEmitter<[string, boolean]>();
  readonly onStatusChanged = this._onStatusChanged.event;

  private activeSessions = new Set<string>();
  private cancellationSources = new Map<string, vscode.CancellationTokenSource>();

  // Conversation history per session (for multi-turn)
  private sessionHistory = new Map<string, vscode.LanguageModelChatMessage[]>();

  constructor(private readonly modelService: ModelService) {}

  async sendMessage(params: SendMessageParams): Promise<void> {
    const sessionId = params.newSession
      ? randomUUID()
      : params.sessionId ?? randomUUID();

    // Prevent concurrent access to same session (same pattern as Kotlin)
    if (this.activeSessions.has(sessionId)) {
      throw new Error(`Session ${sessionId} is already processing a message`);
    }

    this.activeSessions.add(sessionId);
    this._onStatusChanged.fire([sessionId, true]);

    const handler = new ProgressHandler(sessionId, this._onEvent);
    const cts = new vscode.CancellationTokenSource();
    this.cancellationSources.set(sessionId, cts);

    try {
      // Emit session ready
      handler.emitSessionReady(sessionId);

      // Select model
      const model = await this.modelService.selectModel(params.model);
      if (!model) {
        throw new Error(
          'No Copilot language model available. Is GitHub Copilot Chat installed and signed in?'
        );
      }

      // Build message history for multi-turn conversations
      if (params.newSession || !this.sessionHistory.has(sessionId)) {
        this.sessionHistory.set(sessionId, []);
      }

      const history = this.sessionHistory.get(sessionId)!;
      history.push(vscode.LanguageModelChatMessage.User(params.message));

      // Generate turn ID
      const turnId = randomUUID();
      handler.emitTurnIdSync(turnId);
      handler.emitBegin();

      // Send request with streaming
      const response = await model.sendRequest(history, {}, cts.token);

      // Stream response chunks (equivalent to SilentProgressHandler.onProgress with Reply events)
      let accumulated = '';
      for await (const chunk of response.text) {
        if (cts.token.isCancellationRequested) {
          handler.emitCancel();
          return;
        }

        accumulated += chunk;
        handler.emitReply(chunk, accumulated);
      }

      // Store assistant response in history for multi-turn
      history.push(vscode.LanguageModelChatMessage.Assistant(accumulated));

      handler.emitComplete();
    } catch (err) {
      if (cts.token.isCancellationRequested) {
        handler.emitCancel();
      } else {
        handler.emitError(err);
      }
    } finally {
      this.activeSessions.delete(sessionId);
      this.cancellationSources.delete(sessionId);
      cts.dispose();
      this._onStatusChanged.fire([sessionId, false]);
    }
  }

  /**
   * Stop generation for a session.
   * IntelliJ version uses reflection to call Copilot's private onCancel().
   * VS Code version uses standard CancellationToken.
   */
  stopGeneration(sessionId?: string): void {
    if (sessionId) {
      const cts = this.cancellationSources.get(sessionId);
      cts?.cancel();
    } else {
      // Cancel all active sessions
      for (const cts of this.cancellationSources.values()) {
        cts.cancel();
      }
    }
  }

  getActiveSessionIds(): string[] {
    return Array.from(this.activeSessions);
  }

  isSessionActive(sessionId: string): boolean {
    return this.activeSessions.has(sessionId);
  }

  clearSessionHistory(sessionId: string): void {
    this.sessionHistory.delete(sessionId);
  }

  dispose(): void {
    this._onEvent.dispose();
    this._onStatusChanged.dispose();
    for (const cts of this.cancellationSources.values()) {
      cts.dispose();
    }
    this.cancellationSources.clear();
    this.sessionHistory.clear();
  }
}
