# Copilot Internals

This documents the internal Copilot plugin APIs this project depends on. All of these are from the decompiled `github-copilot-intellij-1.5.65-243` plugin and may change between versions.

## Key Services

### CopilotAgentSessionManager
- **Level:** `@Service(Service.Level.PROJECT)`
- **Access:** `project.service<CopilotAgentSessionManager>()`
- **Key methods:**
  - `createSession(callback)` — creates a new session, returns `Session` with `.id`
  - `getLastActiveSession()` — returns the most recent session or null
  - `activateSession(sessionId)` — makes a session the active one
  - `sendMessage(sessionId, message, dataContext, *progressHandlers)` — sends a message through the full Copilot pipeline

### ChatModeService
- **Level:** `@Service(Service.Level.PROJECT)`
- **Access:** `project.service<ChatModeService>()`
- **Key properties:**
  - `chatModes: StateFlow<List<ChatMode>>` — all available modes
  - `currentMode: StateFlow<ChatMode>` — currently selected mode
- **Key methods:**
  - `switchToMode(mode)` — requires EDT

### CompositeModelService
- **Level:** `@Service(Service.Level.APP)`
- **Access:** `ApplicationManager.getApplication().getService(CompositeModelService::class.java)`
- **Key properties:**
  - `models: ScopedModelStateFlowAccessor<CopilotModel>` — use `.unscoped.value` for unfiltered list

### ShowChatToolWindowsListener
- **Type:** Message bus listener interface
- **Topic:** `ShowChatToolWindowsListener.Companion.TOPIC`
- **Usage:** `project.messageBus.syncPublisher(ShowChatToolWindowsListener.Companion.TOPIC).showChatToolWindow()`
- This is the only difference between silent and non-silent mode

## Data Keys (CopilotAgentDataKeys)

Passed via `SimpleDataContext` to `sendMessage()`:

| Key | Type | Purpose |
|---|---|---|
| `MODEL_ID` | `ModelId` | Override model selection |
| `CONTEXT_FILES` | `List<VirtualFile>` | Files to include as context |
| `CONTEXT_SELECTIONS` | `List<FileChatReference>` | Selected code references |
| `INCLUDE_CURRENT_FILE` | `Boolean` | Include the active editor file |
| `AGENT_SLUG` | `String` | Target a specific agent |

## ModelId Construction

`ModelId` is a sealed class hierarchy:

```
ModelId
├── BuiltIn
│   ├── ById(id)
│   ├── ByName(name)
│   └── ByFamily(family)
└── BYOK
    ├── ById(id, provider)
    ├── ByName(name, provider)
    └── ByFamily(family, provider)
```

Use the builder: `ModelId.Companion.forModel(copilotModel).byId()`

## ChatMode

Data class with fields: `id`, `name`, `kind`, `isBuiltIn`, `uri`, `description`, `customTools`, `model`, `handOffs`

Built-in kinds:
- `"Ask"` — question/answer mode
- `"Edit"` — code editing mode
- `"Agent"` — agent mode with tool use

Helper methods: `isAskKind()`, `isEditKind()`, `isAgentKind()`, `isCustomAgent()`

## Progress Handler

`AbstractCopilotAgentConversationProgressHandler` dispatches `CopilotAgentConversationProgressEvent` subtypes to:

| Method | When |
|---|---|
| `onBegin()` | Processing starts |
| `onProgress(ConversationProgressValue)` | Streaming data — reply chunks, steps, references, confirmations |
| `onComplete(ConversationProgressValue)` | Done — final reply, updated documents, suggested title |
| `onError(ConversationError)` | Error or filtered response |
| `onUnauthorized(Unauthorized)` | Auth failure |
| `onSyncConversationId(String)` | Server-side conversation ID |
| `onSyncModelInformation(name, provider, multiplier)` | Model details |
| `onCancel()` | Cancelled |

## ConversationProgressValue Key Fields

Used in both `onProgress` and `onComplete`:

| Field | Type | Notes |
|---|---|---|
| `reply` | `String?` | Text chunk (progress) or final text (complete) |
| `hideText` | `Boolean` | UI hint — Copilot sets this `true` in agent mode. Our handler ignores it and captures reply text regardless, since we are a programmatic consumer. The reply text in agent mode may also come through `AgentRound.reply` in `editAgentRounds`. |
| `conversationId` | `String` | Server conversation ID |
| `turnId` | `String` | Current turn ID |
| `parentTurnId` | `String?` | Parent turn for nested agent calls |
| `references` | `List<*>?` | Referenced files/symbols |
| `steps` | `List<Step>?` | Agent tool calls with status/title |
| `confirmationRequest` | `ConfirmationRequest?` | Agent asking for approval |
| `notifications` | `List<*>?` | Agent notifications |
| `annotations` | `List<*>?` | Reply annotations |
| `editAgentRounds` | `List<AgentRound>?` | Code edit rounds |
| `updatedDocuments` | `List<*>?` | Files modified (complete only) |
| `codeEdits` | `List<*>?` | Code edits with updated documents (complete only) |
| `suggestedTitle` | `String?` | Suggested conversation title (complete only) |

## AgentToolCall (Tool Call Detail)

From `AgentRound.toolCalls` — the detailed view of each tool call the agent makes. This is the data behind our `ToolCallUpdate` event.

**Class:** `com.github.copilot.chat.conversation.agent.rpc.message.AgentToolCall`

| Field | Type | Notes |
|---|---|---|
| `id` | `String?` | Unique tool call identifier |
| `name` | `String?` | Tool name (e.g. `readFile`, `searchFiles`, `editFile`) |
| `toolType` | `String?` | Tool category |
| `progressMessage` | `String?` | Status text while running |
| `status` | `String?` | `running`, `completed`, `failed` |
| `input` | `Map<String, Object>?` | Request payload — the arguments sent to the tool |
| `inputMessage` | `String?` | Human-readable input description |
| `error` | `String?` | Error message if the tool call failed |
| `result` | `List<ToolCallResultData>?` | Response payload — populated on completion |

## ToolCallResultData

**Class:** `com.github.copilot.chat.conversation.agent.rpc.message.ToolCallResultData`

| Field | Type | Notes |
|---|---|---|
| `type` | `String` | Result type — `"text"` or `"data"` |
| `value` | `Object` | Raw value — string for text type, structured object for data type |

Computed properties:
- `getTextValue()` — returns `value` as `String` when `type == "text"`, null otherwise
- `getDataValue()` — returns `value` as `ToolCallDataValue` when `type == "data"`, null otherwise

## AgentRound

**Class:** `com.github.copilot.chat.conversation.agent.rpc.message.AgentRound`

| Field | Type | Notes |
|---|---|---|
| `roundId` | `Int` | Round number within the agent execution |
| `reply` | `String?` | Agent's text reply for this round |
| `toolCalls` | `List<AgentToolCall>?` | Tool calls made in this round |

Delivered via `ConversationProgressValue.editAgentRounds` in both `onProgress` and `onComplete`.

## Step (High-Level Tool Status)

**Class:** `com.github.copilot.chat.conversation.agent.rpc.message.Step`

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | Step identifier |
| `title` | `String` | Display title (e.g. "Reading file.kt") |
| `description` | `String` | Detailed description |
| `status` | `String` | Mutable — `running`, `completed`, `failed`, `cancelled` |
| `error` | `StepError?` | Error details if failed |

Helper methods: `isRunning()`, `isCompleted()`, `isFailed()`, `isCancelled()`, `setRunning()`, `setCompleted()`, `setFailed(error)`, `setCancelled()`

Steps are the high-level summary (what you see in the Copilot UI). `AgentToolCall` provides the full request/response detail.

## Threading

- `ChatModeService.switchToMode()` — requires EDT
- `CopilotAgentSessionController.sendMessage()` — requires EDT (read access)
- `CopilotChatServiceImpl` wraps its call in `withContext(Dispatchers.EDT)` — we do the same
- Progress handler callbacks fire from Copilot's internal threads — not guaranteed EDT
