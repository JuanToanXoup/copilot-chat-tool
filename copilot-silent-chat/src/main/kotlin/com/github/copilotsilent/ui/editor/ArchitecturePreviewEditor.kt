package com.github.copilotsilent.ui.editor

import com.github.copilotsilent.model.ArchitectureNodeDetailListener
import com.github.copilotsilent.ui.webview.JcefBrowserPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.google.gson.JsonParser
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * JCEF-based preview editor for .c4.json architecture files.
 * Renders the C4 level as an interactive React Flow graph.
 *
 * Follows the deferred-load pattern (JCEF-FILE-EDITOR.md):
 * - Browser created with deferLoad=true (no URL until component is in Swing hierarchy)
 * - Skips about:blank in bridge injection
 * - onBridgeReady only pushes set-mode (not file data)
 * - React component sends 'file-editor-ready' after mounting → Kotlin responds with data
 * - Listens for document changes to re-push content
 */
class ArchitecturePreviewEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val log = Logger.getInstance(ArchitecturePreviewEditor::class.java)

    private val panel: JcefBrowserPanel? = if (JcefBrowserPanel.isSupported()) {
        JcefBrowserPanel(this, deferLoad = true).also { p ->
            p.messageHandler = { msg -> handleBridgeMessage(msg) }
            p.onBridgeReady = {
                // Only set mode here. Don't push file data yet — the React
                // component may not have mounted (React 18 batching).
                // ArchitectureView will send 'file-editor-ready' once mounted.
                p.pushData("set-mode", """{"mode":"architecture"}""")
            }
        }
    } else null

    private val fallbackLabel = JLabel("JCEF not available")
    private var suppressDocSync = false

    init {
        // Watch for text editor changes → update preview
        val document = FileDocumentManager.getInstance().getDocument(file)
        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!suppressDocSync) pushFileContent()
            }
        }, this)
    }

    private fun pushFileContent() {
        val push = Runnable {
            ReadAction.run<Throwable> {
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@run
                val text = document.text
                // Compute basePath (directory containing the file)
                val basePath = file.parent?.path ?: ""
                val payload = """{"level":$text,"basePath":"$basePath"}"""
                panel?.pushData("c4-file", payload)
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
                "openFile" -> {
                    val path = json.get("path")?.asString ?: return
                    val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
                    ApplicationManager.getApplication().invokeLater {
                        FileEditorManager.getInstance(project).openFile(vf, true)
                    }
                }
                "showNodeDetail" -> {
                    // Publish to MessageBus — tool window subscribes via ArchitectureNodeDetailListener
                    val detailJson = raw
                    ApplicationManager.getApplication().invokeLater {
                        project.messageBus
                            .syncPublisher(ArchitectureNodeDetailListener.TOPIC)
                            .onNodeDetail(detailJson)
                        ToolWindowManager.getInstance(project)
                            .getToolWindow("Copilot Chat (React)")
                            ?.activate(null)
                    }
                }
                else -> log.info("Unhandled architecture command: $command")
            }
        } catch (e: Exception) {
            log.warn("Failed to handle architecture bridge message: $raw", e)
        }
    }

    override fun getComponent(): JComponent = panel?.component ?: fallbackLabel
    override fun getPreferredFocusedComponent(): JComponent? = panel?.component
    override fun getName(): String = "Architecture Preview"
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
