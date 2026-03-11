package com.citi.assist.service

import com.github.copilot.agent.chatMode.ChatModeService
import com.github.copilot.chat.conversation.agent.rpc.command.CopilotModel
import com.github.copilot.model.CompositeModelService
import com.citi.assist.model.ModelsUpdateListener
import com.citi.assist.model.ModesUpdateListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Collects Copilot's StateFlows (models, modes) and publishes updates
 * to MessageBus topics. This moves the flow collection out of WebViewBridge
 * so any subscriber can observe model/mode changes.
 *
 * Initialized on project open via [StateFlowBroadcasterInitializer].
 */
@Service(Service.Level.PROJECT)
class StateFlowBroadcaster(
    private val project: Project,
) : Disposable {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun dispose() {
        coroutineScope.cancel()
    }

    private val log = Logger.getInstance(StateFlowBroadcaster::class.java)

    private val chatModeService: ChatModeService
        get() = project.service<ChatModeService>()

    private val compositeModelService: CompositeModelService
        get() = ApplicationManager.getApplication().getService(CompositeModelService::class.java)

    fun init() {
        collectModelsFlow()
        collectModesFlow()
        log.info("StateFlowBroadcaster initialized")
    }

    private fun collectModelsFlow() {
        coroutineScope.launch {
            @Suppress("UNCHECKED_CAST")
            val modelsFlow = compositeModelService.models.unscoped
            modelsFlow.collect { models ->
                val modelList = (models as? List<CopilotModel>) ?: return@collect
                project.messageBus
                    .syncPublisher(ModelsUpdateListener.TOPIC)
                    .onModelsUpdated(modelList)
            }
        }
    }

    private fun collectModesFlow() {
        coroutineScope.launch {
            chatModeService.chatModes.collect { modes ->
                val currentMode = chatModeService.currentMode.value
                project.messageBus
                    .syncPublisher(ModesUpdateListener.TOPIC)
                    .onModesUpdated(modes, currentMode)
            }
        }
    }
}
