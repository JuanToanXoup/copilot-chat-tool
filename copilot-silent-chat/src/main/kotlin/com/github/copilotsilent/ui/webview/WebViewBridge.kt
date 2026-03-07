package com.github.copilotsilent.ui.webview

import com.github.copilot.chat.conversation.agent.rpc.command.ChatMode
import com.github.copilot.chat.conversation.agent.rpc.command.CopilotModel
import com.github.copilotsilent.model.ArchitectureNodeDetailListener
import com.github.copilotsilent.model.ModelsUpdateListener
import com.github.copilotsilent.model.ModesUpdateListener
import com.github.copilotsilent.model.SilentChatEvent
import com.github.copilotsilent.model.SilentChatListener
import com.github.copilotsilent.service.CopilotSilentChatService
import com.github.copilotsilent.store.SessionEntry
import com.github.copilotsilent.store.SessionStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Thin mediator between the JCEF browser and the plugin's MessageBus topics.
 *
 * Outbound (Kotlin → JS): subscribes to MessageBus topics and calls pushData().
 * Inbound (JS → Kotlin): routes commands to services via handleMessage().
 *
 * All cleanup is handled by connect(parentDisposable) — no manual detach needed.
 */
class WebViewBridge(
    private val project: Project,
    private val panel: JcefBrowserPanel,
    parentDisposable: Disposable,
) {

    private val log = Logger.getInstance(WebViewBridge::class.java)
    private val gson = Gson()

    private val service: CopilotSilentChatService
        get() = project.service<CopilotSilentChatService>()

    private val sessionStore: SessionStore
        get() = project.service<SessionStore>()

    fun attach() {
        panel.messageHandler = ::handleMessage

        // Push current state for late attach
        pushModels(service.getAvailableModels())
        pushModes(service.getAvailableModes(), service.getCurrentMode())
    }

    /**
     * Subscribes to all MessageBus topics. Connection auto-disconnects
     * when parentDisposable is disposed — no manual cleanup needed.
     */
    init {
        val connection = project.messageBus.connect(parentDisposable)

        connection.subscribe(SilentChatListener.TOPIC, object : SilentChatListener {
            override fun onEvent(sessionId: String, event: SilentChatEvent) {
                sendEventToJs(event)
            }
        })

        connection.subscribe(ModelsUpdateListener.TOPIC, ModelsUpdateListener { models ->
            pushModels(models)
        })

        connection.subscribe(ModesUpdateListener.TOPIC, ModesUpdateListener { modes, currentMode ->
            pushModes(modes, currentMode)
        })

        connection.subscribe(ArchitectureNodeDetailListener.TOPIC, ArchitectureNodeDetailListener { nodeDetailJson ->
            panel.pushData("node-detail", nodeDetailJson)
        })
    }

    private fun pushModels(models: List<CopilotModel>) {
        val data = models.map { model ->
            mapOf("id" to model.id, "name" to model.modelName)
        }
        panel.pushData("models", gson.toJson(data))
    }

    private fun pushModes(modes: List<ChatMode>, currentMode: ChatMode?) {
        val data = mapOf(
            "modes" to modes.map { mode ->
                mapOf("id" to mode.id, "name" to mode.name, "kind" to mode.kind)
            },
            "currentModeId" to currentMode?.id,
        )
        panel.pushData("modes", gson.toJson(data))
    }

    fun pushLog(level: String, tag: String, message: String) {
        val data = mapOf("level" to level, "tag" to tag, "message" to message)
        panel.pushData("log", gson.toJson(data))
    }

    /**
     * Handles messages from the React UI (JS -> Kotlin).
     */
    private fun handleMessage(raw: String) {
        try {
            val json = JsonParser.parseString(raw).asJsonObject
            val command = json.get("command")?.asString ?: return

            when (command) {
                "sendMessage" -> handleSendMessage(json)
                "stopGeneration" -> service.stopGeneration()
                "getModels" -> handleGetModels()
                "getModes" -> handleGetModes()
                "getSessions" -> handleGetSessions()
                "getPlaybooks" -> handleGetPlaybooks()
                "getSession" -> handleGetSession(json)
                "openFile" -> handleOpenFile(json)
                "listArchitectureFiles" -> handleListArchitectureFiles()
                "loadC4File" -> handleLoadC4File(json)
                "listPlaybookFiles" -> handleListPlaybookFiles()
                "loadPlaybookFile" -> handleLoadPlaybookFile(json)
                else -> log.warn("Unknown bridge command: $command")
            }
        } catch (e: Exception) {
            log.warn("Failed to handle bridge message: $raw", e)
        }
    }

    private fun handleSendMessage(json: com.google.gson.JsonObject) {
        val message = json.get("message")?.asString ?: return
        val modelId = json.get("modelId")?.asString
        val modeId = json.get("modeId")?.asString
        val newSession = json.get("newSession")?.asBoolean ?: false
        val silent = json.get("silent")?.asBoolean ?: true
        val sessionId = json.get("sessionId")?.asString

        val model = if (modelId != null) {
            service.getAvailableModels().find { it.id == modelId }
        } else null

        val mode = if (modeId != null) {
            service.getAvailableModes().find { it.id == modeId }
        } else null

        // Record prompt so SessionStore can persist it with the message entry.
        // For new sessions, we use a pending key that gets resolved on SessionReady.
        val promptKey = sessionId ?: SessionStore.PENDING_PROMPT_KEY
        sessionStore.recordPrompt(promptKey, message)

        service.sendMessage(
            message = message,
            sessionId = sessionId,
            model = model,
            mode = mode,
            newSession = newSession,
            silent = silent,
        )
    }

    private fun handleGetModels() {
        pushModels(service.getAvailableModels())
    }

    private fun handleGetModes() {
        pushModes(service.getAvailableModes(), service.getCurrentMode())
    }

    private fun handleGetSessions() {
        val sessions = sessionStore.allSessions().map { session ->
            mapOf(
                "sessionId" to session.sessionId,
                "playbookId" to session.playbookId,
                "startTime" to session.startTime,
                "endTime" to session.endTime,
                "status" to session.status.name,
                "durationMs" to session.durationMs,
                "entries" to session.entries.map { entryToMap(it) },
            )
        }
        panel.pushData("sessions", gson.toJson(sessions))
    }

    private fun handleGetPlaybooks() {
        val playbooks = sessionStore.allPlaybooks().map { pb ->
            mapOf(
                "id" to pb.id,
                "startTime" to pb.startTime,
                "endTime" to pb.endTime,
                "durationMs" to pb.durationMs,
                "chatSessions" to pb.chatSessions.map { session ->
                    mapOf(
                        "sessionId" to session.sessionId,
                        "playbookId" to session.playbookId,
                        "startTime" to session.startTime,
                        "endTime" to session.endTime,
                        "status" to session.status.name,
                        "durationMs" to session.durationMs,
                        "entries" to session.entries.map { entryToMap(it) },
                    )
                },
            )
        }
        panel.pushData("playbooks", gson.toJson(playbooks))
    }

    private fun handleGetSession(json: com.google.gson.JsonObject) {
        val sessionId = json.get("sessionId")?.asString ?: return
        val session = sessionStore.getSession(sessionId) ?: return
        val data = mapOf(
            "sessionId" to session.sessionId,
            "playbookId" to session.playbookId,
            "startTime" to session.startTime,
            "endTime" to session.endTime,
            "status" to session.status.name,
            "durationMs" to session.durationMs,
            "entries" to session.entries.map { entryToMap(it) },
        )
        panel.pushData("session", gson.toJson(data))
    }

    private fun handleOpenFile(json: com.google.gson.JsonObject) {
        val path = json.get("path")?.asString ?: return
        val line = json.get("line")?.asInt
        val vf = LocalFileSystem.getInstance().findFileByPath(path)
        if (vf == null) {
            log.warn("File not found: $path")
            return
        }
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    private fun handleListArchitectureFiles() {
        // Run on pooled thread — this is called from the CEF thread and does file I/O
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val basePath = project.basePath
                if (basePath == null) {
                    log.warn("handleListArchitectureFiles: project.basePath is null")
                    panel.pushData("architecture-files", "[]")
                    return@executeOnPooledThread
                }
                val citiDir = File(basePath, ".citi-ai")
                val archDirs = findSubdirs(citiDir, "architecture")
                log.info("handleListArchitectureFiles: found ${archDirs.size} architecture dirs under ${citiDir.absolutePath}")
                if (archDirs.isEmpty()) {
                    panel.pushData("architecture-files", "[]")
                    return@executeOnPooledThread
                }
                val files = archDirs.flatMap { dir ->
                    dir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.toList()
                }.sortedBy { it.name }
                    .mapNotNull { file ->
                        try {
                            val json = JsonParser.parseString(file.readText()).asJsonObject
                            mapOf(
                                "fileName" to file.name,
                                "filePath" to file.absolutePath,
                                "title" to (json.get("title")?.asString ?: file.nameWithoutExtension),
                                "level" to (json.get("level")?.asInt ?: 0),
                                "nodeCount" to (json.getAsJsonArray("nodes")?.size() ?: 0),
                            )
                        } catch (e: Exception) {
                            log.warn("Failed to parse architecture file: ${file.name}", e)
                            null
                        }
                    }
                log.info("handleListArchitectureFiles: found ${files.size} files, pushing to JS")
                panel.pushData("architecture-files", gson.toJson(files))
            } catch (e: Exception) {
                log.warn("handleListArchitectureFiles failed", e)
                panel.pushData("architecture-files", "[]")
            }
        }
    }

    private fun handleListPlaybookFiles() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val basePath = project.basePath
                if (basePath == null) {
                    panel.pushData("playbook-files", "[]")
                    return@executeOnPooledThread
                }
                val citiDir = File(basePath, ".citi-ai")
                val pbDirs = findSubdirs(citiDir, "playbook")
                if (pbDirs.isEmpty()) {
                    panel.pushData("playbook-files", "[]")
                    return@executeOnPooledThread
                }
                val files = pbDirs.flatMap { dir ->
                    dir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.toList()
                }.sortedBy { it.name }
                    .mapNotNull { file ->
                        try {
                            val json = JsonParser.parseString(file.readText()).asJsonObject
                            mapOf(
                                "fileName" to file.name,
                                "filePath" to file.absolutePath,
                                "name" to (json.get("name")?.asString ?: file.nameWithoutExtension),
                                "stepCount" to (json.getAsJsonArray("steps")?.size() ?: 0),
                            )
                        } catch (e: Exception) {
                            log.warn("Failed to parse playbook file: ${file.name}", e)
                            null
                        }
                    }
                panel.pushData("playbook-files", gson.toJson(files))
            } catch (e: Exception) {
                log.warn("handleListPlaybookFiles failed", e)
                panel.pushData("playbook-files", "[]")
            }
        }
    }

    private fun handleLoadPlaybookFile(json: com.google.gson.JsonObject) {
        val path = json.get("path")?.asString ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(path)
        if (vf == null) {
            log.warn("loadPlaybookFile: file not found: $path")
            return
        }
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    /**
     * Finds all subdirectories under [parent] whose name starts with [prefix].
     * e.g. findSubdirs(citiDir, "playbook") matches "playbook", "playbooks", "playbook-v2"
     */
    private fun findSubdirs(parent: File, prefix: String): List<File> {
        if (!parent.isDirectory) return emptyList()
        return parent.listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }
            ?.toList() ?: emptyList()
    }

    private fun handleLoadC4File(json: com.google.gson.JsonObject) {
        val path = json.get("path")?.asString ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val file = File(path)
                if (!file.exists()) {
                    log.warn("loadC4File: file not found: $path")
                    return@executeOnPooledThread
                }
                val text = file.readText()
                val basePath = file.parent ?: ""
                panel.pushData("c4-file", """{"level":$text,"basePath":"$basePath"}""")
            } catch (e: Exception) {
                log.warn("loadC4File failed: $path", e)
            }
        }
    }

    private fun entryToMap(entry: SessionEntry): Map<String, Any?> = when (entry) {
        is SessionEntry.Message -> mapOf(
            "id" to entry.id,
            "entryType" to "message",
            "turnId" to entry.turnId,
            "startTime" to entry.startTime,
            "endTime" to entry.endTime,
            "status" to entry.status,
            "durationMs" to entry.durationMs,
            "prompt" to entry.prompt,
            "response" to entry.response,
            "replyLength" to entry.replyLength,
        )
        is SessionEntry.ToolCall -> mapOf(
            "id" to entry.id,
            "entryType" to "tool_call",
            "turnId" to entry.turnId,
            "startTime" to entry.startTime,
            "endTime" to entry.endTime,
            "status" to entry.status,
            "durationMs" to entry.durationMs,
            "toolName" to entry.toolName,
            "toolType" to entry.toolType,
            "input" to entry.input,
            "inputMessage" to entry.inputMessage,
            "output" to entry.output,
            "error" to entry.error,
            "progressMessage" to entry.progressMessage,
            "roundId" to entry.roundId,
        )
        is SessionEntry.Step -> mapOf(
            "id" to entry.id,
            "entryType" to "step",
            "turnId" to entry.turnId,
            "startTime" to entry.startTime,
            "endTime" to entry.endTime,
            "status" to entry.status,
            "durationMs" to entry.durationMs,
            "title" to entry.title,
            "description" to entry.description,
        )
    }

    /**
     * Sends a SilentChatEvent to the React UI (Kotlin -> JS).
     */
    private fun sendEventToJs(event: SilentChatEvent) {
        log.info("sendEventToJs: ${event::class.simpleName}")
        pushLog("INFO", "Event", formatEventLog(event))
        val data = when (event) {
            is SilentChatEvent.SessionReady -> mapOf("event" to "SessionReady", "sessionId" to event.sessionId)
            is SilentChatEvent.Begin -> mapOf("event" to "Begin")
            is SilentChatEvent.ConversationIdSync -> mapOf("event" to "ConversationIdSync", "conversationId" to event.conversationId)
            is SilentChatEvent.TurnIdSync -> mapOf("event" to "TurnIdSync", "turnId" to event.turnId, "parentTurnId" to event.parentTurnId)
            is SilentChatEvent.Reply -> mapOf(
                "event" to "Reply",
                "delta" to event.delta,
                "accumulated" to event.accumulated,
                "parentTurnId" to event.parentTurnId,
            )
            is SilentChatEvent.Steps -> mapOf(
                "event" to "Steps",
                "steps" to event.steps.map { step ->
                    mapOf("id" to step.id, "title" to step.title, "description" to step.description, "status" to step.status)
                },
            )
            is SilentChatEvent.ToolCallUpdate -> mapOf(
                "event" to "ToolCallUpdate",
                "sessionId" to event.sessionId,
                "turnId" to event.turnId,
                "parentTurnId" to event.parentTurnId,
                "roundId" to event.roundId,
                "toolCallId" to event.toolCallId,
                "toolName" to event.toolName,
                "toolType" to event.toolType,
                "input" to event.input,
                "inputMessage" to event.inputMessage,
                "status" to event.status,
                "result" to event.result?.map { mapOf("type" to it.type, "value" to it.value) },
                "error" to event.error,
                "progressMessage" to event.progressMessage,
                "durationMs" to event.durationMs,
            )
            is SilentChatEvent.ConfirmationRequest -> mapOf(
                "event" to "ConfirmationRequest",
                "title" to event.request.title,
                "message" to event.request.message,
            )
            is SilentChatEvent.References -> mapOf("event" to "References", "count" to event.references.size)
            is SilentChatEvent.Notifications -> mapOf("event" to "Notifications", "count" to event.notifications.size)
            is SilentChatEvent.EditAgentRound -> mapOf(
                "event" to "EditAgentRound",
                "roundId" to event.round.roundId,
            )
            is SilentChatEvent.UpdatedDocuments -> mapOf("event" to "UpdatedDocuments", "count" to event.documents.size)
            is SilentChatEvent.SuggestedTitle -> mapOf("event" to "SuggestedTitle", "title" to event.title)
            is SilentChatEvent.Complete -> mapOf("event" to "Complete", "fullReply" to event.fullReply)
            is SilentChatEvent.Filter -> mapOf("event" to "Filter", "message" to event.message)
            is SilentChatEvent.Error -> mapOf(
                "event" to "Error",
                "message" to event.message,
                "code" to event.code,
            )
            is SilentChatEvent.Unauthorized -> mapOf("event" to "Unauthorized")
            is SilentChatEvent.Cancel -> mapOf("event" to "Cancel")
            is SilentChatEvent.ModelInformation -> mapOf(
                "event" to "ModelInformation",
                "modelName" to event.modelName,
                "providerName" to event.modelProviderName,
            )
        }
        val withTimestamp = data + ("timestamp" to event.timestamp)
        panel.pushData("event", gson.toJson(withTimestamp))
    }

    private fun formatEventLog(event: SilentChatEvent): String = when (event) {
        is SilentChatEvent.SessionReady -> "SessionReady sessionId=${event.sessionId}"
        is SilentChatEvent.Begin -> "Begin"
        is SilentChatEvent.TurnIdSync -> "TurnIdSync turnId=${event.turnId} parentTurnId=${event.parentTurnId}"
        is SilentChatEvent.Reply -> "Reply delta=${event.delta.length}ch accumulated=${event.accumulated.length}ch"
        is SilentChatEvent.Steps -> "Steps count=${event.steps.size} [${event.steps.joinToString { "${it.id}:${it.title}:${it.status}" }}]"
        is SilentChatEvent.ToolCallUpdate -> "ToolCall ${event.toolName} [${event.toolType}] status=${event.status} id=${event.toolCallId}${event.durationMs?.let { " ${it}ms" } ?: ""}"
        is SilentChatEvent.EditAgentRound -> "EditAgentRound roundId=${event.round.roundId} toolCalls=${event.round.toolCalls?.size ?: 0} reply=${event.round.reply?.length ?: 0}ch"
        is SilentChatEvent.Complete -> "Complete replyLength=${event.fullReply.length}ch"
        is SilentChatEvent.Error -> "Error code=${event.code} message=${event.message}"
        is SilentChatEvent.Cancel -> "Cancel"
        is SilentChatEvent.References -> "References count=${event.references.size}"
        is SilentChatEvent.ConfirmationRequest -> "ConfirmationRequest title=${event.request.title}"
        is SilentChatEvent.Notifications -> "Notifications count=${event.notifications.size}"
        is SilentChatEvent.UpdatedDocuments -> "UpdatedDocuments count=${event.documents.size}"
        is SilentChatEvent.SuggestedTitle -> "SuggestedTitle: ${event.title}"
        is SilentChatEvent.Filter -> "Filter: ${event.message}"
        is SilentChatEvent.Unauthorized -> "Unauthorized"
        is SilentChatEvent.ModelInformation -> "ModelInfo model=${event.modelName} provider=${event.modelProviderName}"
        is SilentChatEvent.ConversationIdSync -> "ConversationIdSync ${event.conversationId}"
    }
}
