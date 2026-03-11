package com.github.copilotsilent.ui.editor

import com.github.copilotsilent.model.PlaybookStepDetailListener
import com.github.copilotsilent.orchestrator.PlaybookProgressListener
import com.github.copilotsilent.ui.webview.JcefBrowserPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.google.gson.JsonParser
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * JCEF-based preview editor for playbook JSON files.
 * Renders the step DAG as an interactive React Flow graph.
 */
class PlaybookPreviewEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val log = Logger.getInstance(PlaybookPreviewEditor::class.java)

    private val panel: JcefBrowserPanel? = if (JcefBrowserPanel.isSupported()) {
        JcefBrowserPanel(this, deferLoad = true).also { p ->
            p.messageHandler = { msg -> handleBridgeMessage(msg) }
            p.onBridgeReady = {
                p.pushData("set-mode", """{"mode":"playbook"}""")
            }
        }
    } else null

    private val fallbackLabel = JLabel("JCEF not available")

    init {
        val document = FileDocumentManager.getInstance().getDocument(file)
        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                pushFileContent()
            }
        }, this)

        // Subscribe to playbook execution progress → forward to the DAG view
        val conn = project.messageBus.connect(this)
        conn.subscribe(PlaybookProgressListener.TOPIC, PlaybookProgressListener { progressJson ->
            panel?.pushData("playbook-progress", progressJson)
        })
    }

    private fun pushFileContent() {
        val push = Runnable {
            ReadAction.run<Throwable> {
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@run
                val text = document.text
                // Wrap with _filePath so the DAG view knows which file to run
                val filePath = file.path
                val payload = """{"_filePath":"$filePath","_raw":$text}"""
                panel?.pushData("playbook-file", payload)
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            push.run()
        } else {
            ApplicationManager.getApplication().invokeLater(push)
        }
    }

    private fun handleBridgeMessage(raw: String) {
        try {
            val json = JsonParser.parseString(raw).asJsonObject
            val command = json.get("command")?.asString ?: return

            when (command) {
                "file-editor-ready" -> pushFileContent()
                "showStepDetail" -> {
                    val detailJson = raw
                    ApplicationManager.getApplication().invokeLater {
                        project.messageBus
                            .syncPublisher(PlaybookStepDetailListener.TOPIC)
                            .onStepDetail(detailJson)
                        ToolWindowManager.getInstance(project)
                            .getToolWindow("Copilot Chat (React)")
                            ?.activate(null)
                    }
                }
                else -> log.info("Unhandled playbook command: $command")
            }
        } catch (e: Exception) {
            log.warn("Failed to handle playbook bridge message: $raw", e)
        }
    }

    override fun getComponent(): JComponent = panel?.component ?: fallbackLabel
    override fun getPreferredFocusedComponent(): JComponent? = panel?.component
    override fun getName(): String = "Playbook Preview"
    override fun isValid(): Boolean = file.isValid
    override fun isModified(): Boolean = false
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file

    override fun dispose() {
        panel?.let { Disposer.dispose(it) }
    }
}
