import * as vscode from 'vscode';
import { ModelInfo } from '../model/types';

/**
 * ModelService — equivalent of StateFlowBroadcaster.kt
 *
 * Discovers available Copilot language models via the VS Code Language Model API
 * and broadcasts updates to subscribers.
 */
export class ModelService implements vscode.Disposable {
  private _onModelsUpdated = new vscode.EventEmitter<ModelInfo[]>();
  readonly onModelsUpdated = this._onModelsUpdated.event;

  private cachedModels: ModelInfo[] = [];
  private disposables: vscode.Disposable[] = [];

  async initialize(): Promise<void> {
    // Listen for model changes
    if (vscode.lm.onDidChangeChatModels) {
      this.disposables.push(
        vscode.lm.onDidChangeChatModels(() => this.refreshModels())
      );
    }

    // Initial model discovery
    await this.refreshModels();
  }

  private async refreshModels(): Promise<void> {
    try {
      const models = await vscode.lm.selectChatModels({ vendor: 'copilot' });
      this.cachedModels = models.map((m) => ({
        id: m.id,
        name: m.name,
        vendor: m.vendor,
        family: m.family,
      }));
      this._onModelsUpdated.fire(this.cachedModels);
    } catch {
      // Copilot may not be available yet
      this.cachedModels = [];
    }
  }

  getModels(): ModelInfo[] {
    return this.cachedModels;
  }

  async selectModel(family?: string): Promise<vscode.LanguageModelChat | undefined> {
    const selector: vscode.LanguageModelChatSelector = { vendor: 'copilot' };
    if (family) {
      selector.family = family;
    }
    const models = await vscode.lm.selectChatModels(selector);
    return models[0];
  }

  dispose(): void {
    this._onModelsUpdated.dispose();
    this.disposables.forEach((d) => d.dispose());
  }
}
