# Usage

## Basic — Send a Single Message

```kotlin
val service = project.service<CopilotSilentChatService>()

service.sendMessage(
    message = "explain this function",
    onEvent = { event ->
        when (event) {
            is SilentChatEvent.Reply -> {
                // Each streamed chunk
                println(event.delta)        // new text, e.g. "The function"
                println(event.accumulated)  // running total
            }
            is SilentChatEvent.Complete -> {
                // Final result
                println(event.fullReply)
            }
            is SilentChatEvent.Error -> {
                println("Error: ${event.message}")
            }
            else -> {}
        }
    }
)
```

## Options

### Select a Model

```kotlin
val models = service.getAvailableModels()  // List<CopilotModel>
val gpt4 = models.find { it.modelName.contains("gpt-4") }

service.sendMessage(
    message = "hello",
    model = gpt4,
    onEvent = { ... }
)
```

### Select a Mode

```kotlin
val modes = service.getAvailableModes()  // List<ChatMode>
val agentMode = modes.find { it.isAgentKind() }

// Option 1: pass mode per-request (switches globally — not safe for parallel)
service.sendMessage(message = "hello", mode = agentMode, onEvent = { ... })

// Option 2: set mode once before parallel calls (safe)
ApplicationManager.getApplication().invokeLater {
    service.switchMode(agentMode)
}
// then call sendMessage() without mode param
```

### New Session vs Reuse

```kotlin
// Reuse last active session (default)
service.sendMessage(message = "hello", onEvent = { ... })

// Force a new session
service.sendMessage(message = "hello", newSession = true, onEvent = { ... })

// Continue a specific session
service.sendMessage(message = "follow up", sessionId = savedId, onEvent = { ... })
```

### Silent vs Tool Window

```kotlin
// Silent — no tool window (default)
service.sendMessage(message = "hello", silent = true, onEvent = { ... })

// Open the tool window, same as normal Copilot
service.sendMessage(message = "hello", silent = false, onEvent = { ... })
```

## Session Management

The `SessionReady` event gives you the session ID to reuse for follow-up messages:

```kotlin
var sessionId: String? = null

service.sendMessage(
    message = "what does this project do?",
    newSession = true,
    onEvent = { event ->
        when (event) {
            is SilentChatEvent.SessionReady -> sessionId = event.sessionId
            is SilentChatEvent.Complete -> {
                // Now send a follow-up on the same session
                service.sendMessage(
                    message = "what are the main classes?",
                    sessionId = sessionId,
                    onEvent = { ... }
                )
            }
            else -> {}
        }
    }
)
```

## Orchestrator — Multiple Requests

```kotlin
val orchestrator = project.service<ChatOrchestrator>()
```

### Parallel — Each Gets Its Own Session

```kotlin
orchestrator.sendParallel(
    requests = listOf(
        ChatRequest("explain function A"),
        ChatRequest("explain function B"),
        ChatRequest("explain function C"),
    ),
    onEvent = { index, event ->
        when (event) {
            is SilentChatEvent.Complete -> {
                println("Request $index done: ${event.fullReply.take(100)}")
            }
            else -> {}
        }
    }
)
```

### Sequential — Same Session, Ordered

```kotlin
orchestrator.sendSequential(
    requests = listOf(
        ChatRequest("what does this project do?"),
        ChatRequest("what are the main classes?"),   // has context from first answer
        ChatRequest("suggest improvements"),          // has context from both
    ),
    onEvent = { index, event ->
        when (event) {
            is SilentChatEvent.Complete -> {
                println("Step $index done: ${event.fullReply.take(100)}")
            }
            else -> {}
        }
    }
)
```

Sequential stops on error — if request 1 fails, requests 2 and 3 are skipped.

## Tool Call Tracking

Every tool call the agent makes (file reads, searches, code edits, etc.) emits `ToolCallUpdate` events with the full request, response, timing, and session context.

### Observing Tool Calls

```kotlin
service.sendMessage(
    message = "refactor this class",
    mode = agentMode,
    newSession = true,
    onEvent = { event ->
        when (event) {
            is SilentChatEvent.ToolCallUpdate -> {
                println("Session: ${event.sessionId}")
                println("Tool:    ${event.toolName} (${event.toolType})")
                println("Status:  ${event.status}")
                println("Input:   ${event.input}")

                if (event.status == "completed") {
                    println("Result:  ${event.result}")
                    println("Took:    ${event.durationMs}ms")
                }
                if (event.error != null) {
                    println("Error:   ${event.error}")
                }
            }
            else -> {}
        }
    }
)
```

### ToolCallUpdate Fields

| Field | Type | Description |
|---|---|---|
| `sessionId` | `String` | Session this tool call belongs to |
| `turnId` | `String?` | Current conversation turn |
| `parentTurnId` | `String?` | Parent turn (for nested agent calls) |
| `roundId` | `Int` | Agent round number |
| `toolCallId` | `String?` | Unique tool call ID |
| `toolName` | `String?` | Tool name (e.g. `readFile`, `searchFiles`, `editFile`) |
| `toolType` | `String?` | Tool category |
| `input` | `Map<String, Any>?` | Request payload (e.g. `{"path": "/src/Main.kt"}`) |
| `inputMessage` | `String?` | Human-readable input description |
| `status` | `String?` | `running` → `completed` / `failed` |
| `result` | `List<ToolCallResult>?` | Response data (populated on completion) |
| `error` | `String?` | Error message (populated on failure) |
| `progressMessage` | `String?` | Status text while running |
| `durationMs` | `Long?` | Wall-clock time from start to completion |

### Timing

Duration is measured client-side using `System.nanoTime()`:
- When a tool call ID is first seen → start time recorded
- When status changes to a terminal state (`completed`/`failed`) → `durationMs` calculated
- Events are deduplicated — only emitted when status actually changes

### Relating Tool Calls to Sessions

Every `ToolCallUpdate` carries `sessionId`, so you can group tool calls by session in parallel scenarios:

```kotlin
val toolCallsBySession = mutableMapOf<String, MutableList<SilentChatEvent.ToolCallUpdate>>()

orchestrator.sendParallel(
    requests = listOf(
        ChatRequest("refactor class A"),
        ChatRequest("refactor class B"),
    ),
    onEvent = { index, event ->
        if (event is SilentChatEvent.ToolCallUpdate) {
            toolCallsBySession
                .getOrPut(event.sessionId) { mutableListOf() }
                .add(event)
        }
    }
)
```

### Two Levels of Tool Call Visibility

| Event | Granularity | Data |
|---|---|---|
| `Steps` | High-level | `id`, `title`, `description`, `status` — what you see in the Copilot UI |
| `ToolCallUpdate` | Full detail | Name, input payload, result payload, timing, session context |

Both fire during the same agent execution. `Steps` is a summary; `ToolCallUpdate` is the full picture.

## Event Timestamps

Every `SilentChatEvent` carries a `timestamp` field (epoch millis, `System.currentTimeMillis()`). This lets you compute durations between any pair of events:

```kotlin
var beginTime: Long = 0

service.sendMessage(
    message = "hello",
    onEvent = { event ->
        when (event) {
            is SilentChatEvent.Begin -> beginTime = event.timestamp
            is SilentChatEvent.Complete -> {
                val totalMs = event.timestamp - beginTime
                println("Total time: ${totalMs}ms")
            }
            else -> {}
        }
    }
)
```

In the webview UI, every event pushed to JS also includes `timestamp` for client-side timing analysis.

## All Events

Every event includes `timestamp: Long` (epoch millis).

| Event | Description |
|---|---|
| `SessionReady(sessionId)` | Session created/resolved, ready to send |
| `Begin` | Copilot started processing |
| `ConversationIdSync(conversationId)` | Server-side conversation ID |
| `TurnIdSync(turnId, parentTurnId)` | Turn tracking |
| `Reply(delta, accumulated, annotations, parentTurnId)` | Streamed text chunk |
| `Steps(steps, parentTurnId)` | Agent tool calls — high-level status |
| `ToolCallUpdate(...)` | Agent tool calls — full request/response with timing and session |
| `EditAgentRound(round, parentTurnId)` | Code edit round |
| `ConfirmationRequest(request)` | Agent asking for user approval |
| `References(references, parentTurnId)` | Files/symbols referenced |
| `Notifications(notifications)` | Notifications from agent |
| `UpdatedDocuments(documents)` | Files modified by agent |
| `SuggestedTitle(title)` | Suggested conversation title |
| `ModelInformation(modelName, providerName, multiplier)` | Which model responded |
| `Complete(fullReply)` | Done — full reply text |
| `Filter(message)` | Response was filtered |
| `Error(message, code, reason, modelName, providerName)` | Error occurred |
| `Unauthorized(unauthorized)` | Auth failure |
| `Cancel` | Request was cancelled |

## Session Guard

The service prevents two messages from running on the same session concurrently. If you try, the second call receives an `Error` event immediately:

```kotlin
service.sendMessage(message = "A", sessionId = "abc", onEvent = { ... })  // runs
service.sendMessage(message = "B", sessionId = "abc", onEvent = { ... })  // Error: already in use
```

Use `newSession = true` or the orchestrator's `sendParallel()` to run concurrent requests safely.
