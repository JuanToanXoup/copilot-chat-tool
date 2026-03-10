import * as vscode from 'vscode';
import { CopilotSilentChatService } from '../service/CopilotSilentChatService';
import { SilentChatEvent } from '../model/events';
import { randomUUID } from 'crypto';

/**
 * ChatOrchestrator — equivalent of ChatOrchestrator.kt
 *
 * Dispatches multiple messages in sequence or parallel,
 * coordinating sessions and collecting results.
 */

export interface OrchestratorMessage {
  message: string;
  model?: string;
  mode?: string;
  sessionId?: string;
}

export interface OrchestratorResult {
  sessionId: string;
  response: string;
  success: boolean;
  error?: string;
}

export class ChatOrchestrator implements vscode.Disposable {
  private disposables: vscode.Disposable[] = [];

  constructor(private readonly chatService: CopilotSilentChatService) {}

  /**
   * Send messages sequentially, each waiting for the previous to complete.
   */
  async sendSequential(messages: OrchestratorMessage[]): Promise<OrchestratorResult[]> {
    const results: OrchestratorResult[] = [];

    for (const msg of messages) {
      const result = await this.sendAndCollect(msg);
      results.push(result);

      // Stop on error
      if (!result.success) {
        break;
      }
    }

    return results;
  }

  /**
   * Send messages in parallel, all starting at once.
   */
  async sendParallel(messages: OrchestratorMessage[]): Promise<OrchestratorResult[]> {
    const promises = messages.map((msg) => this.sendAndCollect(msg));
    return Promise.all(promises);
  }

  /**
   * Send a single message and collect the full response.
   */
  private async sendAndCollect(msg: OrchestratorMessage): Promise<OrchestratorResult> {
    const sessionId = msg.sessionId ?? randomUUID();
    let accumulated = '';
    let success = true;
    let error: string | undefined;

    // Subscribe to events for this session
    const eventPromise = new Promise<void>((resolve) => {
      const disposable = this.chatService.onEvent(([sid, event]) => {
        if (sid !== sessionId) return;

        switch (event.type) {
          case 'Reply':
            accumulated = event.accumulated;
            break;
          case 'Complete':
            disposable.dispose();
            resolve();
            break;
          case 'Error':
            success = false;
            error = event.message;
            disposable.dispose();
            resolve();
            break;
          case 'Cancel':
            success = false;
            error = 'Cancelled';
            disposable.dispose();
            resolve();
            break;
        }
      });
    });

    // Send the message
    await this.chatService.sendMessage({
      message: msg.message,
      sessionId,
      model: msg.model,
      mode: msg.mode,
    });

    // Wait for completion
    await eventPromise;

    return { sessionId, response: accumulated, success, error };
  }

  dispose(): void {
    this.disposables.forEach((d) => d.dispose());
  }
}
