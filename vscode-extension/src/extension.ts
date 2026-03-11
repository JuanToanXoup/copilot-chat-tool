import * as vscode from 'vscode';
import * as path from 'path';
import { BackendBridge } from './backend-bridge';

let backend: BackendBridge | undefined;
let outputChannel: vscode.OutputChannel;

export async function activate(context: vscode.ExtensionContext) {
    outputChannel = vscode.window.createOutputChannel('Copilot Silent Chat');

    // The fat JAR is bundled at ext/copilot-backend-0.1.0.jar
    const jarPath = path.join(context.extensionPath, 'ext', 'copilot-backend-0.1.0.jar');

    backend = new BackendBridge(jarPath);

    backend.on('log', (msg: string) => {
        outputChannel.append(msg);
    });

    backend.on('statusChanged', (data: { sessionId: string; status: string }) => {
        outputChannel.appendLine(`Session ${data.sessionId}: ${data.status}`);
    });

    backend.on('exit', (code: number) => {
        outputChannel.appendLine(`Backend exited with code ${code}`);
        vscode.window.showWarningMessage('Copilot Silent Chat backend exited unexpectedly.');
    });

    // Start backend
    try {
        await backend.start();
        outputChannel.appendLine('Backend started successfully');

        // Initialize with current workspace
        const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
        if (workspaceFolder) {
            const projectSlug = path.basename(workspaceFolder.uri.fsPath);
            await backend.init(projectSlug);
            outputChannel.appendLine(`Initialized for project: ${projectSlug}`);
        }
    } catch (err: any) {
        outputChannel.appendLine(`Failed to start backend: ${err.message}`);
        vscode.window.showErrorMessage(
            `Copilot Silent Chat: Failed to start backend. Ensure Java 17+ is installed. Error: ${err.message}`
        );
    }

    // Register webview provider
    const provider = new ChatWebviewProvider(context, backend);
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider('copilotSilentChat.chatView', provider)
    );

    // Register commands
    context.subscriptions.push(
        vscode.commands.registerCommand('copilotSilentChat.openChat', () => {
            vscode.commands.executeCommand('copilotSilentChat.chatView.focus');
        }),
        vscode.commands.registerCommand('copilotSilentChat.openSessions', async () => {
            if (!backend) { return; }
            try {
                const sessions = await backend.getSessions();
                outputChannel.appendLine(`Sessions: ${JSON.stringify(sessions, null, 2)}`);
                outputChannel.show();
            } catch (err: any) {
                vscode.window.showErrorMessage(`Failed to load sessions: ${err.message}`);
            }
        })
    );

    context.subscriptions.push({ dispose: () => backend?.dispose() });
}

export function deactivate() {
    backend?.dispose();
}

/**
 * Webview provider that renders the shared React UI.
 * Uses the same webview code as the IntelliJ plugin via npm workspaces.
 */
class ChatWebviewProvider implements vscode.WebviewViewProvider {
    constructor(
        private context: vscode.ExtensionContext,
        private backend: BackendBridge | undefined,
    ) {}

    resolveWebviewView(
        webviewView: vscode.WebviewView,
        _webviewContext: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken,
    ) {
        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: [
                vscode.Uri.joinPath(this.context.extensionUri, 'dist'),
                vscode.Uri.joinPath(this.context.extensionUri, 'webview'),
            ],
        };

        // Bridge: webview ↔ backend
        webviewView.webview.onDidReceiveMessage(async (msg) => {
            if (!this.backend) { return; }

            try {
                switch (msg.command) {
                    case 'getSessions': {
                        const sessions = await this.backend.getSessions();
                        webviewView.webview.postMessage({ channel: 'sessions', payload: sessions });
                        break;
                    }
                    case 'getSession': {
                        const session = await this.backend.getSession(msg.sessionId);
                        webviewView.webview.postMessage({ channel: 'session', payload: session });
                        break;
                    }
                    case 'ping': {
                        const pong = await this.backend.ping();
                        webviewView.webview.postMessage({ channel: 'pong', payload: pong });
                        break;
                    }
                }
            } catch (err: any) {
                webviewView.webview.postMessage({
                    channel: 'error',
                    payload: { message: err.message },
                });
            }
        });

        // Forward backend events to webview
        this.backend?.on('statusChanged', (data) => {
            webviewView.webview.postMessage({ channel: 'status', payload: data });
        });

        webviewView.webview.html = this.getHtml(webviewView.webview);
    }

    private getHtml(webview: vscode.Webview): string {
        // In production, serve the built webview from the shared workspace
        // For now, provide a minimal placeholder that loads the React app
        return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; script-src ${webview.cspSource} 'unsafe-inline'; style-src ${webview.cspSource} 'unsafe-inline';">
    <title>Copilot Silent Chat</title>
    <style>
        body {
            font-family: var(--vscode-font-family);
            color: var(--vscode-foreground);
            background-color: var(--vscode-editor-background);
            padding: 16px;
            margin: 0;
        }
        .status { opacity: 0.7; font-size: 12px; margin-top: 8px; }
    </style>
</head>
<body>
    <h3>Copilot Silent Chat</h3>
    <p>Backend connected. The shared React webview will be integrated here via npm workspaces.</p>
    <p class="status">WebView bridge active — backend communication ready.</p>
    <script>
        const vscode = acquireVsCodeApi();

        // Bridge: postMessage to extension host
        window.addEventListener('message', (event) => {
            const msg = event.data;
            if (msg.channel) {
                // Event from extension host → dispatch to React app
                window.dispatchEvent(new CustomEvent('backend-event', { detail: msg }));
            }
        });

        // Expose bridge for React app
        window.__bridge = {
            postMessage: (json) => vscode.postMessage(JSON.parse(json)),
        };
    </script>
</body>
</html>`;
    }
}
