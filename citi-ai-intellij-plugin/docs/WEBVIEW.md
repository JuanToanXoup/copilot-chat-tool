# Webview UI

A React-based chat UI rendered inside the IDE via JCEF (JetBrains Chromium Embedded Framework), connected to `CopilotSilentChatService` through a bidirectional JavaScript bridge.

## Architecture

```
┌─────────────────────────────────────────────────┐
│  IntelliJ Tool Window ("Copilot Chat (React)")  │
│  ┌───────────────────────────────────────────┐  │
│  │  JBCefBrowser                             │  │
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │  React App (index.html)             │  │  │
│  │  │                                     │  │  │
│  │  │  window.__bridge.postMessage() ─────┼──┼──┼──► JBCefJSQuery ──► WebViewBridge
│  │  │                                     │  │  │      (JS → Kotlin)
│  │  │  CustomEvent('jcef-data') ◄─────────┼──┼──┼──── pushData() via executeJavaScript()
│  │  │                                     │  │  │      (Kotlin → JS, Base64 + UTF-8)
│  │  └─────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
                     │
                     ▼
         CopilotSilentChatService
                     │
                     ▼
        CopilotAgentSessionManager
```

## Components

### Kotlin Side

| File | Role |
|---|---|
| `CopilotWebToolWindowFactory` | Registers the JCEF tool window, creates panel + bridge, manages coroutine scope for StateFlow collection |
| `JcefBrowserPanel` | JCEF browser wrapper — custom resource handler serving React app from classpath, JS bridge injection, `pushData()` for Kotlin→JS |
| `WebViewBridge` | Routes JS commands to service, pushes events/models/modes to JS, subscribes to `ChatModeService` and `CompositeModelService` StateFlows |

### React Side

| File | Role |
|---|---|
| `webview/src/App.tsx` | Main component — chat messages, toolbar (mode/model/session/silent), side panel (steps + tool calls) |
| `webview/src/MarkdownRenderer.tsx` | Markdown rendering with shiki syntax highlighting (VS Code Dark+ theme) |
| `webview/src/bridge.ts` | Typed wrapper around `window.__bridge` — `sendMessage()`, `subscribe()`, `requestModels()`, `requestModes()` |
| `webview/src/types.ts` | TypeScript types for ChatMessage, ToolCall, StepInfo, ModelOption, ModeOption |
| `webview/src/style.css` | Dark theme matching IDE aesthetics |

## Resource Serving

The React app is served from plugin classpath resources via a custom JCEF resource handler, not from the filesystem or a dev server.

```
http://copilot-webview/index.html  →  classpath:/webview/index.html
http://copilot-webview/assets/*    →  classpath:/webview/assets/*
```

`JcefBrowserPanel` registers a `CefRequestHandlerAdapter` that intercepts requests to the `http://copilot-webview/` origin and serves them via `InputStreamResourceHandler`, which reads from the plugin JAR's classpath resources with correct MIME types.

This pattern is required because JCEF cannot load from `jar:file:` URLs or `loadHTML()` for apps with multiple assets (JS chunks, CSS, etc.).

## Bridge Protocol

### JS → Kotlin (React sends via `window.__bridge.postMessage()`)

**sendMessage** — Send a chat message:
```json
{
  "command": "sendMessage",
  "message": "explain this function",
  "modelId": "gpt-4o",
  "modeId": "agent",
  "sessionId": "abc-123",
  "newSession": false,
  "silent": true
}
```

**getModels** — Request available models:
```json
{"command": "getModels"}
```

**getModes** — Request available modes:
```json
{"command": "getModes"}
```

### Kotlin → JS (via `pushData(channel, json)` → `CustomEvent('jcef-data')`)

Data is Base64-encoded on the Kotlin side and decoded in JS using `Uint8Array` + `TextDecoder('utf-8')` to correctly handle multi-byte characters in Copilot replies.

**models** channel — List of available models:
```json
[{"id": "gpt-4o", "name": "GPT-4o"}]
```

**modes** channel — List of available modes with current selection:
```json
{
  "modes": [{"id": "agent", "name": "Agent", "kind": "Agent"}],
  "currentModeId": "agent"
}
```

**event** channel — Any `SilentChatEvent` serialized as JSON with timestamp:
```json
{
  "event": "Reply",
  "delta": "Hello",
  "accumulated": "Hello world",
  "timestamp": 1709654400000
}
```

Every event includes a `timestamp` field (epoch millis) for timing analysis.

### Reactive Model/Mode Updates

Models and modes load asynchronously after the Copilot agent connects. The bridge subscribes to `CompositeModelService.models.unscoped` and `ChatModeService.chatModes` StateFlows and pushes updates to the webview whenever they change, rather than relying on one-shot reads.

## Markdown Rendering

Copilot replies are rendered as rich markdown using:

- **`react-markdown`** with **`remark-gfm`** — parses markdown to React components (supports GFM: tables, strikethrough, task lists)
- **`shiki`** — syntax highlighting using VS Code's TextMate engine with the `dark-plus` theme

Shiki eagerly loads 25 common language grammars (kotlin, java, typescript, python, etc.) at initialization. Unknown languages fall back to plain `<pre>` blocks. Language grammars beyond the initial set are lazy-loaded as separate chunks served by the resource handler.

Inline code uses a yellow text color (`#e2c08d`) to match Copilot's styling.

## How the Bridge Works

1. `CopilotWebToolWindowFactory` creates a `JcefBrowserPanel` and a `WebViewBridge`
2. `JcefBrowserPanel` creates a `JBCefJSQuery` and registers a `CefLoadHandler`
3. When the page loads (`onLoadEnd`), the load handler injects `window.__bridge` into the browser:
   - `postMessage(json)` — calls `JBCefJSQuery.inject()` which routes to the Kotlin handler
4. The React app listens via `window.addEventListener('jcef-data', handler)` on mount
5. On `bridge-ready` event (or immediately if bridge exists), React requests models and modes
6. User types a message → React calls `bridge.sendMessage()` → Kotlin receives via `JBCefJSQuery` → calls `CopilotSilentChatService.sendMessage()`
7. Service fires `SilentChatEvent` callbacks → `WebViewBridge.sendEventToJs()` serializes to JSON with timestamp → `pushData("event", json)` → Base64 encode → `executeJavaScript()` → `CustomEvent('jcef-data')` → React state updates

## Building

### React App

```bash
cd webview
npm install
npm run build    # outputs to src/main/resources/webview/
```

Vite outputs `index.html` + `assets/` directory (JS chunks, CSS) to `src/main/resources/webview/`. All files are served by the custom resource handler at runtime.

### Gradle Integration

The `buildWebview` Gradle task runs `npm run build` automatically before `processResources`:

```bash
./gradlew buildPlugin   # builds webview + Kotlin + packages plugin
```

### Development Mode

For rapid iteration on the React UI without rebuilding the plugin:

```bash
cd webview
npm run dev     # Vite dev server with HMR at localhost:5173
```

Then launch the IDE with `-Dcopilotsilent.webview.dev=true` to load from the dev server instead of classpath resources. The Kotlin bridge works normally in dev mode.

## JCEF Availability

If JCEF is not supported (e.g., remote development), the tool window displays a fallback message. Check with `JBCefApp.isSupported()`.

## UI Features

- **Mode selector** — dropdown populated from `ChatModeService.chatModes` StateFlow
- **Model selector** — dropdown populated from `CompositeModelService.models.unscoped` StateFlow
- **New Session checkbox** — forces a new Copilot session for the next message
- **Silent checkbox** — when checked, doesn't open the Copilot tool window
- **Session badge** — shows truncated session ID for the current conversation
- **Streaming indicator** — blinking cursor during reply streaming
- **Side panel** — shows agent steps and tool calls with status icons, timing, input/output details
- **Markdown rendering** — code blocks with VS Code syntax highlighting, inline code, tables, lists, blockquotes
