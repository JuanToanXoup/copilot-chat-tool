package com.github.copilotsilent.service

import com.github.copilot.agent.CopilotAgentDataKeys
import com.github.copilot.agent.chatMode.ChatModeService
import com.github.copilot.agent.conversation.ConversationProgressHandler
import com.github.copilot.agent.conversation.CopilotAgentConversationProgressEvent
import com.github.copilot.agent.session.CopilotAgentSessionManager
import com.github.copilot.chat.conversation.agent.rpc.command.ChatMode
import com.github.copilot.chat.conversation.agent.rpc.command.CopilotModel
import com.github.copilot.chat.input.ModelId
import com.github.copilot.chat.window.ShowChatToolWindowsListener
import com.github.copilot.model.CompositeModelService
import com.github.copilotsilent.model.SilentChatEvent
import com.github.copilotsilent.model.SilentChatListener
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sends messages through the real Copilot tool window pipeline
 * (CopilotAgentSessionManager.sendMessage) without opening the tool window.
 *
 * This uses the exact same code path as CopilotChatService.query() minus the
 * showChatToolWindow() call. All skills, references, document context, session
 * management, retry logic — everything is handled by the real Copilot infrastructure.
 */
@Service(Service.Level.PROJECT)
class CopilotSilentChatService(
    private val project: Project,
) : Disposable {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun dispose() {
        coroutineScope.cancel()
    }

    private val log = Logger.getInstance(CopilotSilentChatService::class.java)
    private val activeSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val sessionManager: CopilotAgentSessionManager
        get() = project.service<CopilotAgentSessionManager>()

    private val chatModeService: ChatModeService
        get() = project.service<ChatModeService>()

    private val compositeModelService: CompositeModelService
        get() = ApplicationManager.getApplication().getService(CompositeModelService::class.java)

    /**
     * Returns the list of available chat modes from the Copilot agent.
     */
    fun getAvailableModes(): List<ChatMode> {
        return chatModeService.chatModes.value
    }

    /**
     * Returns the currently selected chat mode.
     */
    fun getCurrentMode(): ChatMode {
        return chatModeService.currentMode.value
    }

    /**
     * Switch the chat mode. Must be called on EDT.
     * Set this before parallel sendMessage() calls to avoid race conditions.
     */
    fun switchMode(mode: ChatMode) {
        chatModeService.switchToMode(mode)
    }

    /**
     * Returns the list of available models from the Copilot agent.
     */
    fun getAvailableModels(): List<CopilotModel> {
        @Suppress("UNCHECKED_CAST")
        return compositeModelService.models.unscoped.value as List<CopilotModel>
    }

    /**
     * Stops the current in-progress generation.
     * Invokes the controller's private onCancel() via reflection — Copilot
     * does not expose a public cancel API.
     */
    fun stopGeneration() {
        try {
            val controller = sessionManager.getCurrentSessionController()
            val method = controller::class.java.getDeclaredMethod("onCancel")
            method.isAccessible = true
            method.invoke(controller)
        } catch (e: Exception) {
            log.warn("Failed to stop generation", e)
        }
    }

    /**
     * Send a message through the real Copilot pipeline without opening the tool window.
     *
     * Mirrors CopilotChatServiceImpl.query() but skips showChatToolWindow().
     * Calls CopilotAgentSessionManager.sendMessage() which calls
     * CopilotAgentSessionController.sendMessage() — the exact same code path
     * that the tool window uses.
     *
     * @param model optional CopilotModel to use — passed via DataContext MODEL_ID key
     * @param mode optional ChatMode to switch to before sending — calls ChatModeService.switchToMode()
     * @param newSession if true, always creates a new session instead of reusing the last active one
     * @param silent if true (default), does NOT open the tool window; if false, opens it
     *               exactly like CopilotChatServiceImpl does via ShowChatToolWindowsListener
     */
    private fun publish(sid: String, event: SilentChatEvent) {
        project.messageBus.syncPublisher(SilentChatListener.TOPIC).onEvent(sid, event)
    }

    fun sendMessage(
        message: String,
        sessionId: String? = null,
        model: CopilotModel? = null,
        mode: ChatMode? = ChatModeService.BuiltInChatModes.Agent,
        newSession: Boolean = false,
        silent: Boolean = true,
    ) {
        coroutineScope.launch {
            try {
                // Switch mode if requested — this sets the mode on ChatModeService
                // which CopilotAgentSessionController.sendMessage() reads via
                // chatModeService.getCurrentMode().getValue()
                mode?.let{ switchMode(it) }

                // Get or create session — mirrors CopilotChatServiceImpl.getOrCreateSession()
                val sid = sessionId
                    ?: if (newSession) {
                        val session = sessionManager.createSession { }
                        session.id
                    } else {
                        val lastSession = sessionManager.getLastActiveSession()
                        if (lastSession != null) {
                            lastSession.id
                        } else {
                            val session = sessionManager.createSession { }
                            session.id
                        }
                    }

                // Reject if another call is already using this session
                if (!activeSessions.add(sid)) {
                    log.warn("Session $sid is already in use by another parallel call")
                    publish(sid, SilentChatEvent.Error("Session $sid is already in use"))
                    return@launch
                }

                try {
                    // Activate session — identical to CopilotChatServiceImpl
                    sessionManager.activateSession(sid)

                    publish(sid, SilentChatEvent.SessionReady(sid))

                    // Source: CopilotChatServiceImpl wraps sendMessage in withContext(Dispatchers.EDT)
                    // This is required because CopilotAgentSessionController.sendMessage() accesses
                    // IntelliJ platform model which requires read access on EDT.
                    withContext(Dispatchers.EDT) {
                        // Open tool window if not silent — identical to CopilotChatServiceImpl line:
                        // project.messageBus.syncPublisher(ShowChatToolWindowsListener.TOPIC).showChatToolWindow()
                        if (!silent) {
                            project.messageBus
                                .syncPublisher(ShowChatToolWindowsListener.Companion.TOPIC)
                                .showChatToolWindow()
                        }

                        // Build DataContext — same as CopilotChatServiceImpl builds chatDataContext
                        // Optionally include MODEL_ID to override the model selection
                        val dataContextBuilder = SimpleDataContext.builder()
                        if (model != null) {
                            val modelId = ModelId.Companion.forModel(model).byId()
                            dataContextBuilder.add(CopilotAgentDataKeys.MODEL_ID, modelId)
                        }
                        val dataContext: DataContext = dataContextBuilder.build()

                        // Create progress handler — same as CopilotChatServiceImpl.ProgressHandler
                        // but extends AbstractCopilotAgentConversationProgressHandler to get ALL events
                        val handler = SilentProgressHandler(project, sid)

                        // Call the real sendMessage — identical to CopilotChatServiceImpl
                        // This goes through CopilotAgentSessionController.sendMessage()
                        // which handles all skills, references, document context, etc.
                        @Suppress("UNCHECKED_CAST")
                        val handlers = arrayOf(handler as ConversationProgressHandler<CopilotAgentConversationProgressEvent>)
                        sessionManager.sendMessage(
                            sid,
                            message,
                            dataContext,
                            *handlers,
                        )
                    }
                } finally {
                    activeSessions.remove(sid)
                }

            } catch (e: Exception) {
                log.warn("Failed to send message", e)
                publish("unknown", SilentChatEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }
}
