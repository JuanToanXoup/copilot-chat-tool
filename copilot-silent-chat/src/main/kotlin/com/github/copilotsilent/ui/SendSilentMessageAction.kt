package com.github.copilotsilent.ui

import com.github.copilot.chat.conversation.agent.rpc.command.ChatMode
import com.github.copilot.chat.conversation.agent.rpc.command.CopilotModel
import com.github.copilotsilent.model.SilentChatEvent
import com.github.copilotsilent.service.CopilotSilentChatService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JTextField

class SendSilentMessageAction : AnAction() {

    private val log = Logger.getInstance(SendSilentMessageAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<CopilotSilentChatService>()

        val dialog = SilentChatDialog(project, service)
        if (!dialog.showAndGet()) return

        val message = dialog.getMessage() ?: return
        val selectedMode = dialog.getSelectedMode()
        val selectedModel = dialog.getSelectedModel()
        val newSession = dialog.isNewSession()
        val silent = dialog.isSilent()

        service.sendMessage(
            message = message,
            model = selectedModel,
            mode = selectedMode,
            newSession = newSession,
            silent = silent,
            onEvent = { event ->
                when (event) {
                    is SilentChatEvent.SessionReady -> {
                        log.info("Session ready: ${event.sessionId}")
                    }
                    is SilentChatEvent.Begin -> {
                        log.info("Copilot is thinking...")
                    }
                    is SilentChatEvent.ConversationIdSync -> {
                        log.info("Conversation ID: ${event.conversationId}")
                    }
                    is SilentChatEvent.TurnIdSync -> {
                        log.info("Turn ID: ${event.turnId}, parent: ${event.parentTurnId}")
                    }
                    is SilentChatEvent.References -> {
                        log.info("References: ${event.references.size} items")
                    }
                    is SilentChatEvent.Steps -> {
                        for (step in event.steps) {
                            log.info("Step [${step.status}]: ${step.title}")
                        }
                    }
                    is SilentChatEvent.ConfirmationRequest -> {
                        log.info("Confirmation requested: ${event.request}")
                    }
                    is SilentChatEvent.Notifications -> {
                        log.info("Notifications: ${event.notifications.size} items")
                    }
                    is SilentChatEvent.Reply -> {
                        log.info("Streaming: +${event.delta.length} chars")
                    }
                    is SilentChatEvent.EditAgentRound -> {
                        log.info("Edit agent round: ${event.round}")
                    }
                    is SilentChatEvent.ToolCallUpdate -> {
                        val duration = event.durationMs?.let { " (${it}ms)" } ?: ""
                        log.info("Tool [${event.status}] ${event.toolName}${duration} session=${event.sessionId}")
                    }
                    is SilentChatEvent.UpdatedDocuments -> {
                        log.info("Updated documents: ${event.documents.size}")
                    }
                    is SilentChatEvent.SuggestedTitle -> {
                        log.info("Suggested title: ${event.title}")
                    }
                    is SilentChatEvent.Complete -> {
                        log.info("Complete reply (${event.fullReply.length} chars)")
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(
                                project,
                                event.fullReply.take(2000),
                                "Copilot Reply"
                            )
                        }
                    }
                    is SilentChatEvent.Filter -> {
                        log.warn("Response filtered: ${event.message}")
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, event.message ?: "Response was filtered", "Copilot Filtered")
                        }
                    }
                    is SilentChatEvent.Error -> {
                        log.warn("Error [${event.code}]: ${event.message}")
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, event.message, "Copilot Error")
                        }
                    }
                    is SilentChatEvent.Unauthorized -> {
                        log.warn("Unauthorized: ${event.unauthorized.agentSlug}")
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, "Unauthorized", "Copilot Unauthorized")
                        }
                    }
                    is SilentChatEvent.Cancel -> {
                        log.info("Cancelled")
                    }
                    is SilentChatEvent.ModelInformation -> {
                        log.info("Model info: ${event.modelName}/${event.modelProviderName}")
                    }
                }
            }
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

private class SilentChatDialog(
    private val project: Project,
    private val service: CopilotSilentChatService
) : DialogWrapper(project) {

    private val messageField = JTextField(40)
    private val modeComboBox = JComboBox<ChatModeItem>()
    private val modelComboBox = JComboBox<ModelItem>()
    private val newSessionCheckBox = JCheckBox("New session")
    private val silentCheckBox = JCheckBox("Silent (no tool window)", true)

    init {
        title = "Silent Copilot Chat"
        init()
        populateModes()
        populateModels()
    }

    private fun populateModes() {
        val modes = service.getAvailableModes()
        val currentMode = service.getCurrentMode()
        val comboModel = DefaultComboBoxModel<ChatModeItem>()
        var selectedIndex = 0
        for ((index, mode) in modes.withIndex()) {
            comboModel.addElement(ChatModeItem(mode))
            if (mode.id == currentMode.id) {
                selectedIndex = index
            }
        }
        modeComboBox.model = comboModel
        if (comboModel.size > 0) {
            modeComboBox.selectedIndex = selectedIndex
        }
    }

    private fun populateModels() {
        val models = service.getAvailableModels()
        val comboModel = DefaultComboBoxModel<ModelItem>()
        comboModel.addElement(ModelItem(null))
        for (model in models) {
            comboModel.addElement(ModelItem(model))
        }
        modelComboBox.model = comboModel
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Message:") {
                cell(messageField).focused()
            }
            row("Mode:") {
                cell(modeComboBox)
            }
            row("Model:") {
                cell(modelComboBox)
            }
            row("") {
                cell(newSessionCheckBox)
            }
            row("") {
                cell(silentCheckBox)
            }
        }
    }

    fun getMessage(): String? {
        val text = messageField.text?.trim()
        return if (text.isNullOrEmpty()) null else text
    }

    fun getSelectedMode(): ChatMode? {
        return (modeComboBox.selectedItem as? ChatModeItem)?.mode
    }

    fun getSelectedModel(): CopilotModel? {
        return (modelComboBox.selectedItem as? ModelItem)?.model
    }

    fun isNewSession(): Boolean = newSessionCheckBox.isSelected

    fun isSilent(): Boolean = silentCheckBox.isSelected
}

private data class ChatModeItem(val mode: ChatMode) {
    override fun toString(): String = mode.name
}

private data class ModelItem(val model: CopilotModel?) {
    override fun toString(): String = model?.modelName ?: "(Default)"
}
