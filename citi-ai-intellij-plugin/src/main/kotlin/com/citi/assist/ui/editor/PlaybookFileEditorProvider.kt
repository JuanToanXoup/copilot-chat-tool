package com.citi.assist.ui.editor

import com.citi.assist.ui.webview.JcefBrowserPanel
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Detects playbook JSON files inside .citi-ai/playbooks/ and opens them
 * with a split editor: JSON text on one side, DAG visualization on the other.
 */
class PlaybookFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!file.name.endsWith(".json")) return false
        return file.path.contains(".citi-ai/playbook")
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        if (!JcefBrowserPanel.isSupported()) {
            return TextEditorProvider.getInstance().createEditor(project, file)
        }

        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val previewEditor = PlaybookPreviewEditor(project, file)
        return TextEditorWithPreview(
            textEditor,
            previewEditor,
            "Playbook",
            TextEditorWithPreview.Layout.SHOW_PREVIEW,
        )
    }

    override fun getEditorTypeId(): String = "citi-ai-playbook-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
