# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IntelliJ plugin that sends messages to GitHub Copilot programmatically without opening the tool window. It calls `CopilotAgentSessionManager.sendMessage()` directly — the same code path `CopilotChatServiceImpl.query()` uses, minus the `showChatToolWindow()` call. Includes a JCEF-based React UI for interactive use.

## Build Commands

```bash
./gradlew buildPlugin        # Build the plugin (builds webview + Kotlin + packages)
./gradlew compileKotlin      # Compile Kotlin only
./gradlew buildWebview       # Build React webview only
./gradlew runIde             # Launch sandbox IDE with plugin installed
```

### Webview Development

```bash
cd webview
npm install
npm run build                # Production build → src/main/resources/webview/
npm run dev                  # Vite dev server at localhost:5173 (use -Dcopilotsilent.webview.dev=true)
```

## Architecture

- **`CopilotSilentChatService`** — Project-level service. Uses `CopilotAgentSessionManager.sendMessage()` to send messages through the real Copilot pipeline. Must wrap the call in `withContext(Dispatchers.EDT)` because the underlying code requires EDT read access.
- **`SilentProgressHandler`** — Extends `AbstractCopilotAgentConversationProgressHandler` (same base class the real tool window handler uses). Dispatches progress events to a `SilentChatEvent` callback. Ignores `hideText` flag to capture reply text in agent mode. Also extracts reply text from `AgentRound.reply` in `editAgentRounds`.
- **`SilentChatEvent`** — Sealed class covering all Copilot conversation events (Reply, Steps, Complete, Error, ToolCallUpdate, etc.). Every event carries a `timestamp` (epoch millis).
- **`JcefBrowserPanel`** — JCEF browser wrapper that serves the React app from classpath via a custom resource handler (`http://copilot-webview/` origin). Provides JS↔Kotlin bridge via `JBCefJSQuery` + `pushData()` (Base64 + UTF-8).
- **`WebViewBridge`** — Routes JS commands to service, pushes events back. Subscribes to `ChatModeService.chatModes` and `CompositeModelService.models.unscoped` StateFlows for reactive updates.
- **`webview/`** — React app with markdown rendering (react-markdown + shiki with VS Code Dark+ theme), tool call panel, mode/model selectors.

## Key Constraints

- Compiles against Copilot plugin jars from `../github-copilot-intellij-1.5.65-243/lib/` (compileOnly).
- Always validate assumptions against decompiled Copilot source — never guess internal APIs.
- The `sendMessage` vararg requires explicit cast: `handler as ConversationProgressHandler<CopilotAgentConversationProgressEvent>`.
- Plugin depends on `com.github.copilot` (declared in plugin.xml).
- JCEF cannot load from `jar:file:` URLs — must use custom `CefResourceHandler` for classpath resources.
- `pushData()` uses Base64 + `Uint8Array` + `TextDecoder('utf-8')` — plain `atob()` corrupts multi-byte characters.
- Models and modes load asynchronously after Copilot agent connects — must subscribe to StateFlows, not one-shot read.
