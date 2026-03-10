import * as vscode from 'vscode';
import * as path from 'path';
import { CopilotSilentChatService } from '../service/CopilotSilentChatService';
import { SessionStore } from '../store/SessionStore';
import { ModelService } from '../service/ModelService';
import { WebviewBridge } from './WebviewBridge';

/**
 * WebviewPanel — equivalent of JcefBrowserPanel.kt + CopilotWebToolWindowFactory.kt
 *
 * VS Code's Webview API replaces JCEF. Key differences:
 * - No custom CefResourceHandler needed (VS Code serves from extension URI)
 * - No Base64 UTF-8 encoding hack (postMessage handles encoding)
 * - Built-in CSP support
 * - Webview state persistence via getState/setState
 */
export class CopilotWebviewProvider implements vscode.WebviewViewProvider {
  public static readonly viewType = 'copilot-silent-chat.chatView';

  private bridge?: WebviewBridge;

  constructor(
    private readonly extensionUri: vscode.Uri,
    private readonly chatService: CopilotSilentChatService,
    private readonly sessionStore: SessionStore,
    private readonly modelService: ModelService
  ) {}

  resolveWebviewView(
    webviewView: vscode.WebviewView,
    _context: vscode.WebviewViewResolveContext,
    _token: vscode.CancellationToken
  ): void {
    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [
        vscode.Uri.joinPath(this.extensionUri, 'dist', 'webview'),
      ],
    };

    // Set HTML content
    webviewView.webview.html = this.getHtmlForWebview(webviewView.webview);

    // Create bridge (equivalent of WebViewBridge.kt)
    this.bridge = new WebviewBridge(
      webviewView.webview,
      this.chatService,
      this.sessionStore,
      this.modelService
    );

    // Handle webview visibility changes
    webviewView.onDidChangeVisibility(() => {
      if (webviewView.visible) {
        // Re-push current state when panel becomes visible
        this.bridge?.pushCurrentState();
      }
    });

    webviewView.onDidDispose(() => {
      this.bridge?.dispose();
      this.bridge = undefined;
    });
  }

  /**
   * Generate the HTML content for the webview.
   *
   * IntelliJ version uses a custom CefResourceHandler to serve from classpath.
   * VS Code version uses webview.asWebviewUri() to convert local paths.
   *
   * Dev mode: Set COPILOT_SILENT_WEBVIEW_DEV=true to load from localhost:5173
   */
  private getHtmlForWebview(webview: vscode.Webview): string {
    const devMode = process.env.COPILOT_SILENT_WEBVIEW_DEV === 'true';

    if (devMode) {
      // Dev mode: load from Vite dev server (equivalent of copilotsilent.webview.dev=true)
      return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Copilot Silent Chat</title>
</head>
<body>
  <div id="root"></div>
  <script type="module" src="http://localhost:5173/src/main.tsx"></script>
</body>
</html>`;
    }

    // Production: serve bundled single-file HTML from dist/webview
    const webviewPath = vscode.Uri.joinPath(this.extensionUri, 'dist', 'webview');

    // For single-file build (vite-plugin-singlefile), we inline everything
    // But we still need CSP nonce for VS Code webview security
    const nonce = getNonce();

    const scriptUri = webview.asWebviewUri(
      vscode.Uri.joinPath(webviewPath, 'assets', 'index.js')
    );
    const styleUri = webview.asWebviewUri(
      vscode.Uri.joinPath(webviewPath, 'assets', 'index.css')
    );

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="Content-Security-Policy"
    content="default-src 'none';
      style-src ${webview.cspSource} 'unsafe-inline';
      script-src 'nonce-${nonce}';
      font-src ${webview.cspSource};
      img-src ${webview.cspSource} data:;">
  <link rel="stylesheet" href="${styleUri}">
  <title>Copilot Silent Chat</title>
</head>
<body>
  <div id="root"></div>
  <script nonce="${nonce}" src="${scriptUri}"></script>
</body>
</html>`;
  }
}

function getNonce(): string {
  let text = '';
  const possible = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  for (let i = 0; i < 32; i++) {
    text += possible.charAt(Math.floor(Math.random() * possible.length));
  }
  return text;
}
