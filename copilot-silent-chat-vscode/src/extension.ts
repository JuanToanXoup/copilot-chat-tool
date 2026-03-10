import * as vscode from 'vscode';
import { CopilotSilentChatService } from './service/CopilotSilentChatService';
import { ModelService } from './service/ModelService';
import { SessionStore } from './store/SessionStore';
import { DatabaseManager } from './store/DatabaseManager';
import { CopilotWebviewProvider } from './ui/WebviewPanel';

/**
 * Extension entry point — equivalent of plugin.xml + all *Initializer.kt classes.
 *
 * IntelliJ uses:
 *   - plugin.xml for extension registration
 *   - postStartupActivity for lazy initialization
 *   - @Service(PROJECT) for project-scoped singletons
 *   - MessageBus for inter-service communication
 *
 * VS Code uses:
 *   - package.json contributes for extension registration
 *   - activate() for initialization
 *   - Module-scoped singletons
 *   - vscode.EventEmitter for inter-service communication
 */
export async function activate(context: vscode.ExtensionContext): Promise<void> {
  // === Initialize services (equivalent of postStartupActivity classes) ===

  // ≈ DatabaseManager (SessionStoreInitializer)
  const dbManager = new DatabaseManager(context.globalStorageUri);
  context.subscriptions.push(dbManager);

  // ≈ SessionStore (SessionStoreInitializer)
  const sessionStore = new SessionStore(dbManager);
  context.subscriptions.push(sessionStore);

  // ≈ StateFlowBroadcaster (StateFlowBroadcasterInitializer)
  const modelService = new ModelService();
  await modelService.initialize();
  context.subscriptions.push(modelService);

  // ≈ CopilotSilentChatService (@Service(PROJECT))
  const chatService = new CopilotSilentChatService(modelService);
  context.subscriptions.push(chatService);

  // Wire event subscription: chatService → sessionStore
  // (In IntelliJ: SessionStore subscribes to SilentChatListener.TOPIC)
  context.subscriptions.push(
    chatService.onEvent(([sessionId, event]) => {
      sessionStore.handleEvent(sessionId, event);
    })
  );

  // === Register UI (equivalent of CopilotWebToolWindowFactory in plugin.xml) ===

  const webviewProvider = new CopilotWebviewProvider(
    context.extensionUri,
    chatService,
    sessionStore,
    modelService
  );

  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(
      CopilotWebviewProvider.viewType,
      webviewProvider
    )
  );

  // === Register commands (equivalent of <actions> in plugin.xml) ===

  context.subscriptions.push(
    vscode.commands.registerCommand('copilot-silent-chat.sendMessage', async () => {
      const message = await vscode.window.showInputBox({
        prompt: 'Enter message to send to Copilot',
        placeHolder: 'Type your message...',
      });

      if (message) {
        await chatService.sendMessage({ message });
      }
    })
  );

  context.subscriptions.push(
    vscode.commands.registerCommand('copilot-silent-chat.newSession', () => {
      // Trigger new session via the webview
      vscode.commands.executeCommand('copilot-silent-chat.chatView.focus');
    })
  );

  context.subscriptions.push(
    vscode.commands.registerCommand('copilot-silent-chat.stopGeneration', () => {
      chatService.stopGeneration();
    })
  );

  console.log('[Copilot Silent Chat] Extension activated');
}

export function deactivate(): void {
  console.log('[Copilot Silent Chat] Extension deactivated');
}
