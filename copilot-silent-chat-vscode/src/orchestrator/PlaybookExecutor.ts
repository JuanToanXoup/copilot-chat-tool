import * as vscode from 'vscode';
import { CopilotSilentChatService } from '../service/CopilotSilentChatService';
import { SessionStore } from '../store/SessionStore';
import { ChatOrchestrator, OrchestratorMessage, OrchestratorResult } from './ChatOrchestrator';

/**
 * PlaybookExecutor — equivalent of PlaybookExecutor.kt
 *
 * Executes a sequence of messages (a "playbook") with session tracking
 * and progress reporting.
 */

export interface PlaybookStep {
  key: string;
  prompt: string;
  model?: string;
  mode?: string;
  dependsOn?: string[];
}

export interface PlaybookDefinition {
  name: string;
  steps: PlaybookStep[];
}

export interface PlaybookProgress {
  playbookId: string;
  stepKey: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  result?: OrchestratorResult;
}

export class PlaybookExecutor implements vscode.Disposable {
  private _onProgress = new vscode.EventEmitter<PlaybookProgress>();
  readonly onProgress = this._onProgress.event;

  private orchestrator: ChatOrchestrator;
  private disposables: vscode.Disposable[] = [];

  constructor(
    chatService: CopilotSilentChatService,
    private readonly sessionStore: SessionStore
  ) {
    this.orchestrator = new ChatOrchestrator(chatService);
  }

  async execute(playbook: PlaybookDefinition): Promise<OrchestratorResult[]> {
    const playbookId = this.sessionStore.createPlaybook();
    const results: OrchestratorResult[] = [];
    const completedSteps = new Map<string, OrchestratorResult>();

    // Execute steps respecting dependencies
    const remaining = [...playbook.steps];

    while (remaining.length > 0) {
      // Find steps whose dependencies are all met
      const ready = remaining.filter((step) =>
        !step.dependsOn || step.dependsOn.every((dep) => completedSteps.has(dep))
      );

      if (ready.length === 0 && remaining.length > 0) {
        // Deadlock: remaining steps have unmet dependencies
        for (const step of remaining) {
          this._onProgress.fire({
            playbookId,
            stepKey: step.key,
            status: 'failed',
          });
        }
        break;
      }

      // Execute ready steps in parallel
      const messages: OrchestratorMessage[] = ready.map((step) => ({
        message: step.prompt,
        model: step.model,
        mode: step.mode,
      }));

      // Emit running status
      for (const step of ready) {
        this._onProgress.fire({
          playbookId,
          stepKey: step.key,
          status: 'running',
        });
      }

      const stepResults = await this.orchestrator.sendParallel(messages);

      // Process results
      for (let i = 0; i < ready.length; i++) {
        const step = ready[i];
        const result = stepResults[i];

        completedSteps.set(step.key, result);
        results.push(result);

        // Assign session to playbook
        this.sessionStore.assignSessionToPlaybook(result.sessionId, playbookId);

        this._onProgress.fire({
          playbookId,
          stepKey: step.key,
          status: result.success ? 'completed' : 'failed',
          result,
        });
      }

      // Remove completed steps
      for (const step of ready) {
        const idx = remaining.indexOf(step);
        if (idx >= 0) remaining.splice(idx, 1);
      }
    }

    this.sessionStore.completePlaybook(playbookId);
    return results;
  }

  dispose(): void {
    this._onProgress.dispose();
    this.orchestrator.dispose();
    this.disposables.forEach((d) => d.dispose());
  }
}
