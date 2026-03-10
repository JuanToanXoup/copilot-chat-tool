import * as vscode from 'vscode';
import { CopilotSilentChatService } from '../service/CopilotSilentChatService';
import { SessionStore } from '../store/SessionStore';
import { ModelService } from '../service/ModelService';
import { SilentChatEvent } from '../model/events';

/**
 * WebviewBridge — equivalent of WebViewBridge.kt
 *
 * Routes messages between the webview (React) and extension services.
 *
 * IntelliJ version:
 *   JS → Kotlin: window.__bridge.postMessage(JSON.stringify({command, ...}))
 *   Kotlin → JS: panel.pushData(channel, json) → Base64 + atob + CustomEvent
 *
 * VS Code version:
 *   JS → Extension: vscode.postMessage({command, ...})
 *   Extension → JS: webview.postMessage({channel, payload})
 *
 * The command set is identical to IntelliJ.
 */
export class WebviewBridge implements vscode.Disposable {
  private disposables: vscode.Disposable[] = [];

  constructor(
    private readonly webview: vscode.Webview,
    private readonly chatService: CopilotSilentChatService,
    private readonly sessionStore: SessionStore,
    private readonly modelService: ModelService
  ) {
    // Inbound: Webview → Extension
    this.disposables.push(
      webview.onDidReceiveMessage((msg) => this.handleMessage(msg))
    );

    // Subscribe to chat events → forward to webview
    this.disposables.push(
      chatService.onEvent(([sessionId, event]) => {
        this.sendEventToJs(sessionId, event);
      })
    );

    // Subscribe to status changes
    this.disposables.push(
      chatService.onStatusChanged(([sessionId, isLoading]) => {
        this.pushData('status', { sessionId, isLoading });
      })
    );

    // Subscribe to model updates
    this.disposables.push(
      modelService.onModelsUpdated((models) => {
        this.pushData('models', models.map((m) => ({ id: m.id, name: m.name })));
      })
    );
  }

  /**
   * Handle inbound messages from webview.
   * Same command set as IntelliJ WebViewBridge.handleMessage()
   */
  private async handleMessage(msg: { command: string; [key: string]: any }): Promise<void> {
    try {
      switch (msg.command) {
        case 'sendMessage':
          await this.handleSendMessage(msg);
          break;

        case 'stopGeneration':
          this.chatService.stopGeneration(msg.sessionId);
          break;

        case 'getModels':
          this.pushData('models', this.modelService.getModels().map((m) => ({
            id: m.id,
            name: m.name,
          })));
          break;

        case 'getModes':
          // VS Code doesn't have Copilot chat modes in the same way
          // Provide a default set
          this.pushData('modes', {
            modes: [
              { id: 'chat', name: 'Chat' },
              { id: 'agent', name: 'Agent' },
            ],
            currentModeId: 'chat',
          });
          break;

        case 'getSessions':
          this.pushData('sessions', this.sessionStore.getAllSessions());
          break;

        case 'getPlaybooks':
          this.pushData('playbooks', this.sessionStore.getAllPlaybooks());
          break;

        case 'getSession':
          if (msg.sessionId) {
            const session = this.sessionStore.getSession(msg.sessionId);
            this.pushData('session', session ?? null);
          }
          break;

        case 'openFile':
          await this.handleOpenFile(msg);
          break;

        default:
          console.warn(`[WebviewBridge] Unknown command: ${msg.command}`);
      }
    } catch (err) {
      console.error(`[WebviewBridge] Error handling command '${msg.command}':`, err);
      this.pushData('log', {
        level: 'error',
        message: `Error handling ${msg.command}: ${err}`,
        timestamp: Date.now(),
      });
    }
  }

  private async handleSendMessage(msg: any): Promise<void> {
    const { message, modelId, modeId, sessionId, newSession, silent } = msg;

    // Record prompt for session store association
    if (sessionId) {
      this.sessionStore.recordPrompt(sessionId, message);
    }

    await this.chatService.sendMessage({
      message,
      sessionId,
      model: modelId,
      mode: modeId,
      newSession,
      silent,
    });
  }

  private async handleOpenFile(msg: any): Promise<void> {
    const { filePath, line } = msg;
    if (!filePath) return;

    const uri = vscode.Uri.file(filePath);
    const doc = await vscode.workspace.openTextDocument(uri);
    const editor = await vscode.window.showTextDocument(doc);

    if (line && typeof line === 'number') {
      const position = new vscode.Position(Math.max(0, line - 1), 0);
      editor.selection = new vscode.Selection(position, position);
      editor.revealRange(new vscode.Range(position, position));
    }
  }

  /**
   * Send a chat event to the webview.
   * Equivalent of WebViewBridge.sendEventToJs() in Kotlin.
   */
  private sendEventToJs(sessionId: string, event: SilentChatEvent): void {
    const data = this.eventToData(event);
    this.pushData('event', { sessionId, ...data });

    // Also push structured log (same as Kotlin)
    this.pushData('log', {
      level: 'info',
      message: `[${event.type}] session=${sessionId}`,
      timestamp: event.timestamp,
      data,
    });
  }

  /**
   * Map SilentChatEvent to serializable data.
   * Equivalent of WebViewBridge.eventToMap() in Kotlin.
   */
  private eventToData(event: SilentChatEvent): Record<string, any> {
    switch (event.type) {
      case 'SessionReady':
        return { event: 'SessionReady', sessionId: event.sessionId };
      case 'Begin':
        return { event: 'Begin' };
      case 'Reply':
        return {
          event: 'Reply',
          delta: event.delta,
          accumulated: event.accumulated,
          annotations: event.annotations,
          parentTurnId: event.parentTurnId,
        };
      case 'Steps':
        return { event: 'Steps', steps: event.steps };
      case 'ToolCallUpdate':
        return {
          event: 'ToolCallUpdate',
          id: event.id,
          toolName: event.toolName,
          toolType: event.toolType,
          status: event.status,
          input: event.input,
          inputMessage: event.inputMessage,
          output: event.output,
          error: event.error,
          progressMessage: event.progressMessage,
          result: event.result,
          durationMs: event.durationMs,
          roundId: event.roundId,
        };
      case 'Complete':
        return { event: 'Complete' };
      case 'Error':
        return { event: 'Error', message: event.message };
      case 'Cancel':
        return { event: 'Cancel' };
      case 'ConversationIdSync':
        return { event: 'ConversationIdSync', conversationId: event.conversationId };
      case 'TurnIdSync':
        return { event: 'TurnIdSync', turnId: event.turnId };
      case 'SuggestedTitle':
        return { event: 'SuggestedTitle', title: event.title };
    }
  }

  /**
   * Push data to the webview on a named channel.
   *
   * IntelliJ: panel.pushData(channel, json) → Base64 → atob → CustomEvent('jcef-data')
   * VS Code:  webview.postMessage({channel, payload}) → window.addEventListener('message')
   */
  pushData(channel: string, payload: any): void {
    this.webview.postMessage({ channel, payload });
  }

  /**
   * Push current state to webview (called when panel becomes visible).
   */
  pushCurrentState(): void {
    // Push models
    this.pushData(
      'models',
      this.modelService.getModels().map((m) => ({ id: m.id, name: m.name }))
    );

    // Push modes
    this.pushData('modes', {
      modes: [
        { id: 'chat', name: 'Chat' },
        { id: 'agent', name: 'Agent' },
      ],
      currentModeId: 'chat',
    });
  }

  dispose(): void {
    this.disposables.forEach((d) => d.dispose());
  }
}
