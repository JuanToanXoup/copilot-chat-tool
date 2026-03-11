# Architecture

## Overview

Copilot Silent Chat is an IntelliJ plugin that sends messages to GitHub Copilot programmatically without opening the tool window. It calls the same internal pipeline that the Copilot tool window uses, bypassing only the `showChatToolWindow()` call.

## How It Bypasses the Tool Window

The normal Copilot path:

```
CopilotChatService.query()
    ├── showChatToolWindow()              ← opens the UI
    └── sessionManager.sendMessage()      ← does the work
            └── CopilotAgentSessionController.sendMessage()
                    └── Copilot agent (skills, context, LLM)
```

Our path:

```
CopilotSilentChatService.sendMessage()
    ├── (optional) showChatToolWindow()   ← only if silent=false
    └── sessionManager.sendMessage()      ← same call, same args
            └── CopilotAgentSessionController.sendMessage()
                    └── Copilot agent (skills, context, LLM)
```

`CopilotChatService` is not a listener or interceptor — it's just a caller. It doesn't know about messages sent by other code. `CopilotAgentSessionManager` doesn't care who calls it.

## Package Structure

```
com.citi.assist/
├── model/                           # Data contract
│   └── SilentChatEvent.kt          # Sealed class — every event type, each with timestamp
├── service/                         # Single-request logic
│   ├── CopilotSilentChatService.kt  # Core service — send message, manage sessions
│   └── SilentProgressHandler.kt     # Adapter — Copilot events → SilentChatEvent
├── orchestrator/                    # Multi-request logic
│   └── ChatOrchestrator.kt         # Sequential and parallel dispatching
├── ui/
│   ├── SendSilentMessageAction.kt   # Demo action (Tools menu)
│   └── webview/                     # JCEF-based React UI
│       ├── CopilotWebToolWindowFactory.kt  # Tool window factory
│       ├── JcefBrowserPanel.kt             # JCEF browser + resource handler + JS bridge
│       └── WebViewBridge.kt                # Routes JS↔Kotlin, subscribes to StateFlows

webview/                             # React app (built into src/main/resources/webview/)
├── src/
│   ├── App.tsx                      # Main chat UI component
│   ├── MarkdownRenderer.tsx         # Markdown + shiki syntax highlighting
│   ├── bridge.ts                    # JS↔Kotlin bridge wrapper
│   ├── types.ts                     # TypeScript types
│   └── style.css                    # Dark theme CSS
├── package.json                     # React 19, Vite 6, shiki, react-markdown
└── vite.config.ts                   # Outputs to ../src/main/resources/webview/
```

## Layer Responsibilities

### model/ — Data Contract

`SilentChatEvent` is a sealed class defining every event that can occur during a Copilot conversation. It has no behavior — it's pure data. Any consumer (UI, AI agent, API) uses this to understand what happened.

### service/ — Single Request

`CopilotSilentChatService` handles sending one message. It:

- Manages Copilot session lifecycle (create, reuse, activate)
- Switches chat mode via `ChatModeService.switchToMode()`
- Sets model selection via `CopilotAgentDataKeys.MODEL_ID` in the DataContext
- Toggles tool window visibility via `ShowChatToolWindowsListener`
- Guards against two concurrent messages on the same session using `activeSessions` (a concurrent set)
- Wraps the call in `withContext(Dispatchers.EDT)` because `CopilotAgentSessionController.sendMessage()` requires EDT read access

`SilentProgressHandler` extends `AbstractCopilotAgentConversationProgressHandler` — the same base class the real tool window handler uses. It translates Copilot's internal events into `SilentChatEvent` variants and forwards them to the caller's `onEvent` callback.

### orchestrator/ — Multi-Request

`ChatOrchestrator` decides how multiple requests are dispatched:

- `sendParallel()` — each request gets its own new session, all launch concurrently
- `sendSequential()` — requests share one session, each waits for the previous to complete via `CompletableDeferred`

The orchestrator does not manage parallelism itself — it relies on the service launching separate coroutines and the session guard preventing conflicts.

### ui/webview/ — JCEF React UI

A full chat interface rendered in an IntelliJ tool window via JCEF (embedded Chromium):

- `JcefBrowserPanel` — serves the React app from classpath resources via a custom `CefResourceHandler` on a fake `http://copilot-webview/` origin. Provides bidirectional communication: JS→Kotlin via `JBCefJSQuery`, Kotlin→JS via `pushData()` (Base64-encoded UTF-8 + `CustomEvent`).
- `WebViewBridge` — routes incoming JS commands (`sendMessage`, `getModels`, `getModes`) to `CopilotSilentChatService`, and pushes events back. Subscribes to `ChatModeService.chatModes` and `CompositeModelService.models.unscoped` StateFlows to reactively update the UI when models/modes load asynchronously.
- `CopilotWebToolWindowFactory` — creates the tool window, wires up panel + bridge, manages coroutine scope lifecycle.

The React app (`webview/`) renders markdown replies with shiki syntax highlighting (VS Code Dark+ theme), displays agent steps and tool calls in a side panel, and provides controls for mode, model, session, and silent mode.

### ui/ — Demo Action

`SendSilentMessageAction` is an IntelliJ action in the Tools menu with mode/model dropdowns. Exists for manual testing — deletable.

## Design Patterns

- **Observer** — callers subscribe via `onEvent` callback, receive real-time streaming events
- **Adapter** — `SilentProgressHandler` adapts Copilot's `AbstractCopilotAgentConversationProgressHandler` to our `SilentChatEvent` model
- **Facade** — `CopilotSilentChatService` hides `CopilotAgentSessionManager`, `ChatModeService`, `CompositeModelService`, `DataContext` construction, and EDT threading behind a single `sendMessage()` call

## Threading Model

- `sendMessage()` launches a coroutine on the service's `CoroutineScope`
- Session creation and last-active lookup happen on the coroutine's dispatcher
- `switchToMode()` and `sessionManager.sendMessage()` run on `Dispatchers.EDT` because Copilot's internals require EDT read access
- The `onEvent` callback fires from whatever thread the Copilot progress handler runs on — callers needing EDT must use `invokeLater`
