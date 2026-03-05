package com.github.copilotsilent.ui.webview

import com.github.copilot.agent.chatMode.ChatModeService
import com.github.copilot.chat.conversation.agent.rpc.command.ChatMode
import com.github.copilot.chat.conversation.agent.rpc.command.CopilotModel
import com.github.copilot.model.CompositeModelService
import com.github.copilotsilent.model.SilentChatEvent
import com.github.copilotsilent.service.CopilotSilentChatService
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Routes messages between a JcefBrowserPanel (React UI) and CopilotSilentChatService.
 *
 * JS -> Kotlin: React calls window.__bridge.postMessage(json) -> panel.messageHandler -> handleMessage()
 * Kotlin -> JS: panel.pushData(channel, json) -> CustomEvent('jcef-data') -> React listener
 *
 * Subscribes to Copilot's StateFlows for models and modes, pushing updates
 * to the webview whenever they change (they load asynchronously after agent connect).
 */
class WebViewBridge(
    private val project: Project,
    private val panel: JcefBrowserPanel,
    private val coroutineScope: CoroutineScope,
) {

    private val log = Logger.getInstance(WebViewBridge::class.java)
    private val gson = Gson()

    private val service: CopilotSilentChatService
        get() = project.service<CopilotSilentChatService>()

    private val chatModeService: ChatModeService
        get() = project.service<ChatModeService>()

    private val compositeModelService: CompositeModelService
        get() = ApplicationManager.getApplication().getService(CompositeModelService::class.java)

    private var modelsJob: Job? = null
    private var modesJob: Job? = null

    fun attach() {
        panel.messageHandler = ::handleMessage
        collectModelsFlow()
        collectModesFlow()
    }

    fun detach() {
        modelsJob?.cancel()
        modesJob?.cancel()
        if (panel.messageHandler === ::handleMessage) {
            panel.messageHandler = null
        }
    }

    /**
     * Collects the models StateFlow and pushes updates to the webview
     * whenever the model list changes.
     */
    private fun collectModelsFlow() {
        modelsJob = coroutineScope.launch {
            @Suppress("UNCHECKED_CAST")
            val modelsFlow = compositeModelService.models.unscoped
            modelsFlow.collect { models ->
                val modelList = (models as? List<CopilotModel>) ?: return@collect
                pushModels(modelList)
            }
        }
    }

    /**
     * Collects the chatModes StateFlow and pushes updates to the webview
     * whenever the modes list changes.
     */
    private fun collectModesFlow() {
        modesJob = coroutineScope.launch {
            chatModeService.chatModes.collect { modes ->
                val currentMode = chatModeService.currentMode.value
                pushModes(modes, currentMode)
            }
        }
    }

    private fun pushModels(models: List<CopilotModel>) {
        val data = models.map { model ->
            mapOf("id" to model.id, "name" to model.modelName)
        }
        panel.pushData("models", gson.toJson(data))
    }

    private fun pushModes(modes: List<ChatMode>, currentMode: ChatMode) {
        val data = mapOf(
            "modes" to modes.map { mode ->
                mapOf("id" to mode.id, "name" to mode.name, "kind" to mode.kind)
            },
            "currentModeId" to currentMode.id,
        )
        panel.pushData("modes", gson.toJson(data))
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
                "getModels" -> handleGetModels()
                "getModes" -> handleGetModes()
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

        service.sendMessage(
            message = message,
            sessionId = sessionId,
            model = model,
            mode = mode,
            newSession = newSession,
            silent = silent,
            onEvent = { event -> sendEventToJs(event) }
        )
    }

    private fun handleGetModels() {
        pushModels(service.getAvailableModels())
    }

    private fun handleGetModes() {
        pushModes(service.getAvailableModes(), service.getCurrentMode())
    }

    /**
     * Sends a SilentChatEvent to the React UI (Kotlin -> JS).
     */
    private fun sendEventToJs(event: SilentChatEvent) {
        log.info("sendEventToJs: ${event::class.simpleName}")
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
}
