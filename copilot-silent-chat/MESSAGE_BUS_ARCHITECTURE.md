# MessageBus Architecture

## Overview

This plugin uses IntelliJ's MessageBus (publisher-subscriber pattern) to decouple services from UI and other consumers. No component holds a direct reference to its observers. Events flow through a centralized topic, allowing any number of subscribers to react independently.

## Data Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                        PUBLISHERS                                   │
│                                                                     │
│  CopilotSilentChatService          SilentProgressHandler            │
│  ├─ publish(sid, SessionReady)     ├─ publish(Begin)                │
│  ├─ publish(sid, Error)            ├─ publish(Reply)                │
│  └─ (session lifecycle)            ├─ publish(ToolCallUpdate)       │
│                                    ├─ publish(Complete)             │
│                                    └─ (all progress events)         │
│                                                                     │
│                         │                                           │
│                         ▼                                           │
│              ┌─────────────────────┐                                │
│              │  SilentChatNotifier  │                                │
│              │  .TOPIC             │                                │
│              │  (Project-level)    │                                │
│              └─────────────────────┘                                │
│                         │                                           │
│              ┌──────────┼──────────┐                                │
│              ▼          ▼          ▼                                 │
│                      SUBSCRIBERS                                    │
│                                                                     │
│  WebViewBridge       ChatOrchestrator    SendSilentMessageAction    │
│  (forwards to JS)    (sequences msgs)   (shows dialogs)            │
│                                                                     │
│  [Future: logging]   [Future: status]   [Future: notifications]    │
└─────────────────────────────────────────────────────────────────────┘
```

## Core Components

### Topic: `SilentChatNotifier`

**File:** `model/SilentChatNotifier.kt`

```kotlin
interface SilentChatNotifier {
    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("copilot.silent.chat", SilentChatNotifier::class.java)
    }

    fun onEvent(sessionId: String, event: SilentChatEvent)
}
```

- **Project-level**: scoped to a single open project, auto-disposed on project close.
- Every event carries a `sessionId` so subscribers can filter by session.
- `SilentChatEvent` is a sealed class — exhaustive when-matching guarantees all events are handled.

### Publishers

Publishers call `syncPublisher(SilentChatNotifier.TOPIC).onEvent(sid, event)` to emit events. There are two publishers:

| Publisher | What it publishes | When |
|---|---|---|
| `CopilotSilentChatService` | `SessionReady`, `Error` | Session lifecycle (created, rejected) |
| `SilentProgressHandler` | All other `SilentChatEvent` subtypes | During Copilot conversation progress |

Both use a `publish()` helper:

```kotlin
private fun publish(sid: String, event: SilentChatEvent) {
    project.messageBus.syncPublisher(SilentChatNotifier.TOPIC).onEvent(sid, event)
}
```

### Subscribers

Subscribers call `project.messageBus.connect().subscribe(SilentChatNotifier.TOPIC, ...)`.

| Subscriber | Purpose | Lifecycle |
|---|---|---|
| `WebViewBridge` | Forwards events to JCEF browser via `executeJavaScript()` | Connects in `attach()`, disconnects in `detach()` |
| `ChatOrchestrator` | Tracks `Complete`/`Error` to sequence multi-message flows | Connects per sequential step, disconnects after completion |
| `SendSilentMessageAction` | Logs events, shows result/error dialogs | Connects per action invocation, disconnects on terminal event |

## Rules

### 1. Services publish, they never subscribe to their own topic

`CopilotSilentChatService` and `SilentProgressHandler` only publish. They don't know who is listening. This keeps the service layer clean and testable.

### 2. Always pass a Disposable or disconnect manually

Every `connect()` must have a matching cleanup:

```kotlin
// Option A: tie to a Disposable (preferred for long-lived subscribers)
project.messageBus.connect(parentDisposable).subscribe(...)

// Option B: manual disconnect (for short-lived subscribers)
val conn = project.messageBus.connect()
conn.subscribe(...)
// ... later ...
conn.disconnect()
```

Failing to disconnect causes memory leaks.

### 3. Filter by sessionId when needed

All events carry a `sessionId`. Subscribers that care about a specific session must filter:

```kotlin
override fun onEvent(sessionId: String, event: SilentChatEvent) {
    if (sessionId != myTargetSessionId) return
    // handle event
}
```

The `WebViewBridge` forwards all events (the JS side filters). The `ChatOrchestrator` filters by the session it's sequencing.

### 4. syncPublisher delivers synchronously

`syncPublisher()` calls all subscriber handlers on the **calling thread** before returning. This means:
- Don't hold locks when publishing — subscribers might need them.
- Don't do expensive work in subscriber handlers — it blocks the publisher.
- If a subscriber needs to do slow work, hand it off to a background thread or coroutine.

### 5. The bridge is a mediator, not a controller

`WebViewBridge` holds no state. It:
- Routes JS → Kotlin commands to the service
- Forwards MessageBus events to JS via `pushData()`

It never processes or transforms business logic.

## Adding a New Feature

### Scenario: You need a new component that reacts to chat events

**Step 1:** Subscribe to the existing topic.

```kotlin
class MyNewFeature(private val project: Project) {

    private var connection: MessageBusConnection? = null

    fun start() {
        connection = project.messageBus.connect().also { conn ->
            conn.subscribe(SilentChatNotifier.TOPIC, object : SilentChatNotifier {
                override fun onEvent(sessionId: String, event: SilentChatEvent) {
                    when (event) {
                        is SilentChatEvent.Complete -> handleComplete(sessionId, event)
                        is SilentChatEvent.Error -> handleError(sessionId, event)
                        else -> {} // ignore events you don't care about
                    }
                }
            })
        }
    }

    fun stop() {
        connection?.disconnect()
        connection = null
    }
}
```

That's it. No changes to `CopilotSilentChatService`, `SilentProgressHandler`, or any other existing code.

### Scenario: You need a new event type

**Step 1:** Add a new subclass to `SilentChatEvent`:

```kotlin
data class MyNewEvent(val data: String) : SilentChatEvent()
```

**Step 2:** Publish it from the appropriate place (service or handler):

```kotlin
publish(sid, SilentChatEvent.MyNewEvent("some data"))
```

**Step 3:** Handle it in subscribers that care. The Kotlin compiler will warn about non-exhaustive `when` blocks if you're matching on the sealed class.

### Scenario: You need a completely separate topic

If your feature's events are unrelated to chat (e.g., settings changes, connection status), create a new topic rather than overloading `SilentChatNotifier`:

```kotlin
interface MyFeatureNotifier {
    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("copilot.silent.myfeature", MyFeatureNotifier::class.java)
    }

    fun onSomething(data: MyData)
}
```

**When to create a new topic vs. reuse `SilentChatNotifier`:**
- Same topic: the event is part of the chat conversation lifecycle
- New topic: the event is about a different domain (settings, auth, indexing, etc.)

### Scenario: You need an Application-level topic

Use `@Topic.AppLevel` instead of `@Topic.ProjectLevel` when the event is IDE-wide, not project-specific:

```kotlin
interface GlobalNotifier {
    companion object {
        @Topic.AppLevel
        val TOPIC = Topic.create("copilot.silent.global", GlobalNotifier::class.java)
    }

    fun onGlobalEvent(event: GlobalEvent)
}

// Publish via application bus
ApplicationManager.getApplication().messageBus
    .syncPublisher(GlobalNotifier.TOPIC)
    .onGlobalEvent(event)
```

## Thread Safety Summary

| Operation | Thread | Safe? |
|---|---|---|
| `syncPublisher().onEvent()` | Any thread | Yes, but handlers run on caller's thread |
| `subscribe()` | Any thread | Yes |
| `disconnect()` | Any thread | Yes |
| `executeJavaScript()` (in subscriber) | Any thread | Yes (JCEF handles threading) |
| IDE write operations (in subscriber) | Must be EDT | Use `invokeLater {}` |
| IDE read operations (in subscriber) | Any, but needs ReadAction | Use `ReadAction.run {}` |
