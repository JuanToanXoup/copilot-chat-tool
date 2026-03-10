# VS Code Adaptation: Copilot Silent Chat

## Overview

This document describes how to adapt the IntelliJ Copilot Silent Chat plugin to a VS Code extension, preserving the same architecture while mapping IntelliJ-specific concepts to VS Code equivalents.

## Architecture Mapping: IntelliJ → VS Code

### Layer-by-Layer Comparison

| Layer | IntelliJ | VS Code |
|-------|----------|---------|
| **Language** | Kotlin + React (TypeScript) | TypeScript + React (TypeScript) |
| **Plugin Format** | Gradle → ZIP (JARs) | npm → VSIX |
| **UI Framework** | JCEF (Chromium Embedded) | VS Code Webview API |
| **Event Bus** | IntelliJ MessageBus (Topics) | VS Code `EventEmitter` / `vscode.Event` |
| **Services** | `@Service(PROJECT)` singletons | Extension-scoped singletons (module pattern) |
| **Persistence** | SQLite via JDBC | SQLite via `better-sqlite3` (native) or `sql.js` (WASM) |
| **Copilot Integration** | `CopilotAgentSessionManager.sendMessage()` | Copilot Chat extension commands + Language Model API |
| **Webview Bridge** | `JBCefJSQuery` + `pushData()` | `webview.postMessage()` / `webview.onDidReceiveMessage` |
| **Startup** | `postStartupActivity` | `activate()` in `extension.ts` |
| **Build** | Gradle + Vite | esbuild/webpack + Vite |

---

## 1. Copilot Integration Strategy

### The Core Challenge

In IntelliJ, we call `CopilotAgentSessionManager.sendMessage()` directly — the exact same code path the UI uses. In VS Code, we have **three viable approaches** to programmatically drive Copilot Chat:

### Approach A: VS Code Language Model API (Recommended - Official)

VS Code 1.90+ provides `vscode.lm.selectChatModels()` to access Copilot's language models directly:

```typescript
import * as vscode from 'vscode';

// Select a Copilot model
const [model] = await vscode.lm.selectChatModels({
  vendor: 'copilot',
  family: 'gpt-4o'  // or 'claude-3.5-sonnet', 'o1', etc.
});

// Create a request
const messages = [
  vscode.LanguageModelChatMessage.User('Explain this code'),
];

const response = await model.sendRequest(messages, {}, new vscode.CancellationTokenSource().token);

// Stream the response
for await (const chunk of response.text) {
  console.log(chunk); // delta text
}
```

**Pros:** Official API, stable, model selection, streaming, cancellation support.
**Cons:** No tool calling / agent mode — this is raw LLM access, not the full Copilot agent with workspace context, file editing, terminal commands, etc.

### Approach B: Chat Participant API + Internal Commands (Hybrid)

Register as a Chat Participant to receive user messages in Copilot Chat, then forward to our own UI:

```typescript
const participant = vscode.chat.createChatParticipant('copilot-silent', handler);

async function handler(
  request: vscode.ChatRequest,
  context: vscode.ChatContext,
  stream: vscode.ChatResponseStream,
  token: vscode.CancellationToken
): Promise<vscode.ChatResult> {
  // Access to request.prompt, context.history
  // Can use vscode.lm models or delegate to Copilot
  stream.markdown('Response here');
  return {};
}
```

**Pros:** Integrates with native Copilot Chat UI, access to chat history/context.
**Cons:** We become a *participant* in Copilot's chat, not a *controller* of it.

### Approach C: Execute Copilot Commands via VS Code API (Most Similar to IntelliJ)

Drive the Copilot Chat extension through its registered commands:

```typescript
// Send a message to Copilot Chat programmatically
await vscode.commands.executeCommand(
  'github.copilot.chat.sendMessage',  // or similar internal command
  { message: 'Explain this function' }
);

// Or use the inline chat
await vscode.commands.executeCommand(
  'github.copilot.interactiveEditor.explain'
);
```

**Pros:** Uses full Copilot agent capabilities (tools, workspace context).
**Cons:** Internal commands may change between versions, limited event capture, no streaming API.

### Recommended: Approach A (Language Model API) + Custom Agent Layer

Use `vscode.lm` for raw model access and build our own agent layer on top that mirrors Copilot's agent capabilities. This gives us:
- Full control over the conversation flow
- Tool call interception (our own `SilentProgressHandler` equivalent)
- Model/mode selection
- Session management
- Streaming with event capture

For **agent mode** features (file editing, terminal commands), we can implement tool handlers using VS Code's own APIs (`vscode.workspace.fs`, `vscode.window.createTerminal`).

---

## 2. Directory Structure

```
copilot-silent-chat-vscode/
├── src/
│   ├── extension.ts                    # activate/deactivate (≈ plugin.xml + Initializers)
│   ├── service/
│   │   ├── CopilotSilentChatService.ts # Main service (≈ CopilotSilentChatService.kt)
│   │   ├── ProgressHandler.ts          # Event emitter (≈ SilentProgressHandler.kt)
│   │   ├── ModelService.ts             # Model discovery (≈ StateFlowBroadcaster.kt)
│   │   └── AgentToolHandler.ts         # Tool execution (file edit, terminal, etc.)
│   ├── model/
│   │   ├── events.ts                   # SilentChatEvent types (≈ SilentChatEvent.kt)
│   │   └── types.ts                    # Session, ToolCall, Step types
│   ├── store/
│   │   ├── SessionStore.ts             # Event-sourced persistence (≈ SessionStore.kt)
│   │   └── DatabaseManager.ts          # SQLite wrapper (≈ DatabaseManager.kt)
│   ├── ui/
│   │   ├── WebviewPanel.ts             # Webview provider (≈ JcefBrowserPanel.kt)
│   │   └── WebviewBridge.ts            # Message routing (≈ WebViewBridge.kt)
│   ├── orchestrator/
│   │   ├── ChatOrchestrator.ts         # Multi-message dispatch
│   │   └── PlaybookExecutor.ts         # Playbook execution
│   └── semantic/
│       ├── EmbeddingEngine.ts          # ONNX embeddings
│       └── VectorStore.ts              # sqlite-vec KNN search
├── webview/                            # React app (REUSE from IntelliJ with minor bridge changes)
│   ├── src/
│   │   ├── bridge.ts                   # Adapted for VS Code webview messaging
│   │   ├── App.tsx                     # Same tabs: Chat, Sessions, Logs, Explorer, Playbooks
│   │   ├── chat/
│   │   │   ├── useChat.ts             # Same hook, same event handling
│   │   │   ├── ChatView.tsx
│   │   │   └── ...
│   │   └── ...
│   ├── package.json
│   └── vite.config.ts
├── package.json                        # Extension manifest
├── tsconfig.json
├── esbuild.js                          # Extension bundler
└── .vscodeignore
```

---

## 3. Component Mapping Details

### 3.1 Extension Entry Point (`extension.ts`)

Replaces: `plugin.xml` + `*Initializer.kt` classes

```typescript
export async function activate(context: vscode.ExtensionContext) {
  // ≈ SessionStoreInitializer
  const db = new DatabaseManager(context.globalStorageUri);
  const sessionStore = new SessionStore(db);

  // ≈ StateFlowBroadcasterInitializer
  const modelService = new ModelService();
  await modelService.initialize();

  // ≈ CopilotSilentChatService (project-level service)
  const chatService = new CopilotSilentChatService(modelService);

  // Wire event subscriptions (≈ MessageBus connect)
  chatService.onEvent((sessionId, event) => {
    sessionStore.handleEvent(sessionId, event);
    webviewBridge?.sendEvent(sessionId, event);
  });

  // ≈ CopilotWebToolWindowFactory
  const provider = new CopilotWebviewProvider(context, chatService, sessionStore, modelService);
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider('copilot-silent-chat.chatView', provider)
  );

  // ≈ IndexManagerInitializer (optional, can be deferred)
  // const indexManager = new IndexManager(db);
  // indexManager.indexWorkspace();
}
```

### 3.2 CopilotSilentChatService

Replaces: `CopilotSilentChatService.kt`

```typescript
export class CopilotSilentChatService {
  private _onEvent = new vscode.EventEmitter<[string, SilentChatEvent]>();
  readonly onEvent = this._onEvent.event;

  private activeSessions = new Set<string>();

  async sendMessage(params: {
    message: string;
    sessionId?: string;
    model?: string;
    mode?: string;
    newSession?: boolean;
  }): Promise<void> {
    const sessionId = params.sessionId ?? uuid();

    if (this.activeSessions.has(sessionId)) {
      throw new Error(`Session ${sessionId} is already active`);
    }

    this.activeSessions.add(sessionId);
    const handler = new ProgressHandler(sessionId, this._onEvent);

    try {
      // Select model via Language Model API
      const [model] = await vscode.lm.selectChatModels({
        vendor: 'copilot',
        family: params.model ?? 'gpt-4o'
      });

      handler.emitBegin();
      const cts = new vscode.CancellationTokenSource();

      const messages = [
        vscode.LanguageModelChatMessage.User(params.message)
      ];

      const response = await model.sendRequest(messages, {}, cts.token);

      // Stream response (≈ SilentProgressHandler.onProgress)
      let accumulated = '';
      for await (const chunk of response.text) {
        accumulated += chunk;
        handler.emitReply(chunk, accumulated);
      }

      handler.emitComplete();
    } catch (err) {
      handler.emitError(err);
    } finally {
      this.activeSessions.delete(sessionId);
    }
  }
}
```

### 3.3 ProgressHandler

Replaces: `SilentProgressHandler.kt`

```typescript
export class ProgressHandler {
  constructor(
    private sessionId: string,
    private emitter: vscode.EventEmitter<[string, SilentChatEvent]>
  ) {}

  emitBegin() {
    this.emitter.fire([this.sessionId, { type: 'Begin', timestamp: Date.now() }]);
  }

  emitReply(delta: string, accumulated: string) {
    this.emitter.fire([this.sessionId, {
      type: 'Reply', delta, accumulated, timestamp: Date.now()
    }]);
  }

  emitToolCallUpdate(toolCall: ToolCallUpdate) {
    this.emitter.fire([this.sessionId, {
      type: 'ToolCallUpdate', ...toolCall, timestamp: Date.now()
    }]);
  }

  emitComplete() {
    this.emitter.fire([this.sessionId, { type: 'Complete', timestamp: Date.now() }]);
  }

  emitError(error: unknown) {
    this.emitter.fire([this.sessionId, {
      type: 'Error', message: String(error), timestamp: Date.now()
    }]);
  }
}
```

### 3.4 Webview Bridge

Replaces: `JcefBrowserPanel.kt` + `WebViewBridge.kt`

VS Code's Webview API is much simpler than JCEF — no custom resource handlers or Base64 encoding needed.

**Extension side (`WebviewBridge.ts`):**
```typescript
export class WebviewBridge {
  constructor(
    private webview: vscode.Webview,
    private chatService: CopilotSilentChatService,
    private sessionStore: SessionStore,
    private modelService: ModelService
  ) {
    // Inbound: JS → Extension
    webview.onDidReceiveMessage(msg => this.handleMessage(msg));
  }

  private async handleMessage(msg: { command: string; [key: string]: any }) {
    switch (msg.command) {
      case 'sendMessage':
        await this.chatService.sendMessage(msg);
        break;
      case 'stopGeneration':
        this.chatService.stopGeneration();
        break;
      case 'getModels':
        this.pushData('models', await this.modelService.getModels());
        break;
      case 'getSessions':
        this.pushData('sessions', this.sessionStore.getAllSessions());
        break;
      // ... same command set as IntelliJ WebViewBridge
    }
  }

  // Outbound: Extension → JS (≈ panel.pushData)
  pushData(channel: string, payload: any) {
    this.webview.postMessage({ channel, payload });
  }

  sendEvent(sessionId: string, event: SilentChatEvent) {
    this.pushData('event', { sessionId, ...eventToData(event) });
  }
}
```

**Webview side (`bridge.ts` — adapted):**
```typescript
// VS Code acquires the API object
const vscode = acquireVsCodeApi();

export function postMessage(msg: Record<string, unknown>): void {
  vscode.postMessage(msg);  // was: window.__bridge.postMessage(JSON.stringify(msg))
}

export function subscribe(fn: (data: { channel: string; payload: any }) => void): () => void {
  const handler = (event: MessageEvent) => {
    fn(event.data);  // was: CustomEvent('jcef-data')
  };
  window.addEventListener('message', handler);
  return () => window.removeEventListener('message', handler);
}
```

This is the **only change** needed in the React webview. The rest of the UI (useChat, ChatView, SessionsView, etc.) works unchanged because it already communicates through the `bridge.ts` abstraction.

### 3.5 Session Store & Database

Replaces: `SessionStore.kt` + `DatabaseManager.kt`

Essentially a TypeScript port. Two SQLite options:

| Option | Pros | Cons |
|--------|------|------|
| `better-sqlite3` | Fast, synchronous API, mature | Native module (needs rebuild per platform) |
| `sql.js` | Pure WASM, no native deps | Slower, entire DB in memory |

Recommended: **`better-sqlite3`** with VS Code's native module support (`vscode-rebuild`).

DB location: `context.globalStorageUri` → `~/.vscode/extensions/copilot-silent-chat/sessions.db`

The schema is identical to IntelliJ:
```sql
CREATE TABLE IF NOT EXISTS playbook_runs (id TEXT PRIMARY KEY, start_time INTEGER, end_time INTEGER);
CREATE TABLE IF NOT EXISTS chat_sessions (session_id TEXT PRIMARY KEY, playbook_id TEXT, start_time INTEGER, end_time INTEGER, status TEXT);
CREATE TABLE IF NOT EXISTS session_entries (...); -- same columns
```

### 3.6 Event System

Replaces: IntelliJ MessageBus Topics

```typescript
// IntelliJ: project.messageBus.syncPublisher(SilentChatListener.TOPIC).onEvent(sid, event)
// VS Code:  chatEventEmitter.fire([sid, event])

// IntelliJ: project.messageBus.connect().subscribe(ModelsUpdateListener.TOPIC, listener)
// VS Code:  modelService.onModelsUpdated(listener)

// Pattern: Each service exposes a vscode.Event for its updates
class ModelService {
  private _onModelsUpdated = new vscode.EventEmitter<ModelInfo[]>();
  readonly onModelsUpdated = this._onModelsUpdated.event;
}
```

---

## 4. What Can Be Reused As-Is

| Component | Reuse Level | Notes |
|-----------|-------------|-------|
| React Webview (all `.tsx` files) | **95%** | Only `bridge.ts` needs adaptation |
| `useChat.ts` hook | **100%** | Event handling logic unchanged |
| `ChatView.tsx` + child components | **100%** | No platform-specific code |
| `SessionsView.tsx` | **100%** | Pure React |
| `types.ts` | **100%** | Shared type definitions |
| CSS / styling | **100%** | Already uses VS Code Dark+ theme colors |
| Markdown rendering | **100%** | react-markdown + shiki |
| DB schema | **100%** | Same SQLite schema |
| Event types (`SilentChatEvent`) | **100%** | Direct TypeScript port of sealed class |

### What Needs Porting (Kotlin → TypeScript)

| Component | Effort | Notes |
|-----------|--------|-------|
| `CopilotSilentChatService` | Medium | Different Copilot API surface |
| `SilentProgressHandler` | Low | Simpler in TS (EventEmitter vs MessageBus) |
| `WebViewBridge` | Low | VS Code webview API is simpler than JCEF |
| `SessionStore` | Medium | Direct port, different SQLite library |
| `DatabaseManager` | Medium | Different SQLite library API |
| `StateFlowBroadcaster` | Low | `vscode.lm` API is simpler |
| `ChatOrchestrator` | Low | Direct port |
| Semantic search (ONNX) | Medium | `onnxruntime-node` works in VS Code |

---

## 5. package.json (Extension Manifest)

```json
{
  "name": "copilot-silent-chat",
  "displayName": "Copilot Silent Chat",
  "version": "0.1.0",
  "engines": { "vscode": "^1.90.0" },
  "extensionDependencies": ["github.copilot-chat"],
  "activationEvents": ["onStartupFinished"],
  "main": "./dist/extension.js",
  "contributes": {
    "viewsContainers": {
      "activitybar": [{
        "id": "copilot-silent-chat",
        "title": "Copilot Silent Chat",
        "icon": "resources/icon.svg"
      }]
    },
    "views": {
      "copilot-silent-chat": [{
        "type": "webview",
        "id": "copilot-silent-chat.chatView",
        "name": "Chat"
      }]
    },
    "commands": [{
      "command": "copilot-silent-chat.sendMessage",
      "title": "Send Silent Message to Copilot"
    }]
  }
}
```

---

## 6. Build System

```
Extension:  esbuild (src/ → dist/extension.js)
Webview:    Vite + vite-plugin-singlefile (webview/ → dist/webview/index.html)
Package:    vsce package → .vsix
```

Build commands:
```bash
npm run build           # Build extension + webview
npm run build:ext       # Extension only (esbuild)
npm run build:webview   # Webview only (vite)
npm run dev             # Watch mode for both
npm run package         # Create .vsix
```

---

## 7. Key Differences & Gotchas

1. **No EDT requirement**: VS Code extensions run in a single Node.js thread. No `withContext(Dispatchers.EDT)` needed.

2. **Webview lifecycle**: VS Code webviews can be disposed and re-created (panel hidden/shown). Must handle `onDidChangeViewState` and re-hydrate state. IntelliJ JCEF panels persist.

3. **State persistence**: Use `webview.getState()` / `webview.setState()` for webview-side state across hide/show cycles.

4. **CSP**: VS Code webviews require Content Security Policy headers. The Vite build must account for this (nonce-based scripts).

5. **No reflection needed**: VS Code extension API is TypeScript — no casts or reflection for Copilot integration.

6. **Multi-root workspaces**: VS Code supports multiple workspace folders. Session DB should be scoped per workspace.

7. **Extension host restart**: Unlike IntelliJ which keeps services alive, VS Code extension host can restart. Services must be stateless or restore from DB.
