# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This repository has two parts:

1. **`citi-ai-intellij-plugin/`** — An IntelliJ plugin (Kotlin + React) that programmatically drives GitHub Copilot chat sessions. It calls `CopilotAgentSessionManager.sendMessage()` directly — the same code path the real Copilot UI uses, minus `showChatToolWindow()`. Includes a JCEF-based React UI, session persistence in SQLite, semantic code search via ONNX embeddings, and session replay.

2. **`github-copilot-intellij-1.5.65-243/`** — Extracted Copilot plugin (v1.5.65, IntelliJ 243) used as a compile-only reference. Contains JARs, source JARs, the Node.js language server, agent prompts, and Tree-sitter WASM parsers. Not built — only analyzed and compiled against.

## Build Commands

All commands run from `citi-ai-intellij-plugin/`:

```bash
./gradlew buildPlugin        # Full build: webview + Kotlin + package ZIP
./gradlew compileKotlin      # Kotlin only (fast check)
./gradlew buildWebview       # React webview only
./gradlew runIde             # Launch sandbox IDE with plugin
./gradlew installLocal       # Build + install to local IDE via REST API
```

### Webview Development

```bash
cd citi-ai-intellij-plugin/webview
npm install
npm run build                # Production build (tsc + vite) -> src/main/resources/webview/
npm run dev                  # Vite dev server at localhost:5173
```

Use `-Dciti.assist.webview.dev=true` VM option with `runIde` to load from dev server instead of classpath.

## Architecture

### Kotlin Plugin (`src/main/kotlin/com/citi/assist/`)

- **`service/CopilotSilentChatService`** — Project-level service. Wraps `sendMessage()` in `withContext(Dispatchers.EDT)` (Copilot requires EDT read access). Manages session IDs and conversation state.
- **`service/SilentProgressHandler`** — Extends Copilot's `AbstractCopilotAgentConversationProgressHandler`. Dispatches events as `SilentChatEvent` sealed class. Ignores `hideText` flag to capture agent-mode replies. Extracts reply text from `AgentRound.reply` in `editAgentRounds`.
- **`service/StateFlowBroadcaster`** — Subscribes to Copilot's `ChatModeService.chatModes` and `CompositeModelService.models.unscoped` StateFlows, rebroadcasts via IntelliJ MessageBus topics.
- **`model/SilentChatEvent`** — Sealed class: Reply, Steps, Complete, Error, Cancel, ToolCallUpdate, TurnIdSync, ConversationIdSync, SuggestedTitle, SessionReady. Every event carries `timestamp` (epoch millis).
- **`store/SessionStore`** — Subscribes to `SilentChatListener.TOPIC`, persists all events to SQLite. Stores messages, tool calls, and steps with turn grouping.
- **`store/db/DatabaseManager`** — Raw JDBC + SQLite. Per-project DB at `~/.citi-ai/projects/{slug}/sessions.db`. Schema migration via `ALTER TABLE ADD COLUMN`.
- **`orchestrator/ChatOrchestrator`** — Multi-message dispatch (parallel/sequential).
- **`semantic/`** — ONNX Runtime with all-MiniLM-L6-v2 embeddings + sqlite-vec for vector KNN search. Auto-indexes on project open.
- **`ui/webview/JcefBrowserPanel`** — Serves React app from classpath via custom `CefResourceHandler` (`http://copilot-webview/` origin). JS-Kotlin bridge via `JBCefJSQuery` + `pushData()`.
- **`ui/webview/WebViewBridge`** — Routes JS commands (`sendMessage`, `getSession`, `getSessions`, etc.) to services, pushes events back to JS.

### MessageBus Topics (4 project-level topics)

| Topic | Events |
|-------|--------|
| `SilentChatListener.TOPIC` | All `SilentChatEvent` variants per session |
| `ChatStatusListener.TOPIC` | `isLoading` boolean changes |
| `ModelsUpdateListener.TOPIC` | Available Copilot models list |
| `ModesUpdateListener.TOPIC` | Available chat modes + current mode |

### React Webview (`webview/src/`)

- **`bridge.ts`** — JS-Kotlin bridge. `postMessage()` sends commands, `subscribe()` listens to `CustomEvent('jcef-data')` with `{ channel, payload }`.
- **`App.tsx`** — Tab container: Chat, Sessions, Logs. Manages cross-tab session loading.
- **`chat/useChat.ts`** — Core hook. Manages messages, tool calls, steps, streaming state. `hydrateSession()` transforms DB entries back to Chat UI state for replay.
- **`chat/ChatView.tsx`** — Composes Toolbar + MessageList + InputArea. Accepts `loadSessionId` prop for session replay.
- **`SessionsView.tsx`** — Turn-based hierarchy: sessions > prompts (turns) > tool calls + steps + Copilot response. "Open" button loads session into Chat tab.

### JCEF Bridge Protocol

- **JS -> Kotlin**: `window.__bridge.postMessage(JSON.stringify({ command, ...params }))`
- **Kotlin -> JS**: `panel.pushData(channel, json)` dispatches `CustomEvent('jcef-data')` with Base64 + UTF-8 decoding (plain `atob()` corrupts multi-byte chars).
- **Channels**: `event`, `models`, `modes`, `sessions`, `playbooks`, `session`, `status`

### DB Schema (`session_entries` table)

```
entry_type: 'message' | 'tool_call' | 'step'
turn_id, round_id, start_time, end_time, status, duration_ms
prompt, response, reply_length (message)
tool_name, tool_type, input, input_message, output, error, progress_message (tool_call)
title, description (step)
```

## Key Constraints

- **Copilot dependency**: Compiles against Copilot JARs found automatically from local IDE installations (or set `copilotPluginPath` in `gradle.properties`). The build scans `~/Library/Application Support/JetBrains/*/plugins/github-copilot-intellij/`.
- **No bundled Kotlin stdlib**: `kotlin.stdlib.default.dependency = false` in gradle.properties. IntelliJ provides Kotlin runtime. kotlinx-coroutines must be excluded from all dependencies.
- **SLF4J conflicts**: All dependencies must `exclude(group = "org.slf4j")` — IntelliJ provides its own.
- **Light services**: All 4 services use `@Service(Service.Level.PROJECT)` — NOT registered in plugin.xml. They create their own `CoroutineScope` (Gradle IntelliJ Plugin 1.x doesn't support constructor injection).
- **`sendMessage` cast**: Requires `handler as ConversationProgressHandler<CopilotAgentConversationProgressEvent>` explicit cast.
- **`stopGeneration()`**: Uses reflection to call Copilot's private `onCancel()` — Copilot has no public cancel API.
- **Webview single-file**: Vite builds to a single HTML file via `vite-plugin-singlefile` for classpath serving.

## Reference Plugin Analysis

To inspect the extracted Copilot plugin at `github-copilot-intellij-1.5.65-243/`:

```bash
jar tf github-copilot-intellij-1.5.65-243/lib/core.jar          # List classes
jar xf github-copilot-intellij-1.5.65-243/lib/src/core-src.jar  # Extract sources
```

Key reference files:
- `copilot-agent/dist/assets/agents/*.agent.md` — Agent system prompts
- `copilot-agent/dist/assets/prompts.contributions.json` — Agent registry
- `copilot-agent/dist/api/types.d.ts` — ContextProvider API types
