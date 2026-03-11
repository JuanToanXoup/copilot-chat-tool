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
 * Detects .c4.json files inside .citi-ai/architecture/ and opens them
 * with a split editor: JSON text on one side, React Flow visualization on the other.
 */
class ArchitectureFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!file.name.endsWith(".json")) return false
        return file.path.contains(".citi-ai/architecture")
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        if (!JcefBrowserPanel.isSupported()) {
            return TextEditorProvider.getInstance().createEditor(project, file)
        }

        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val previewEditor = ArchitecturePreviewEditor(project, file)
        return TextEditorWithPreview(
            textEditor,
            previewEditor,
            "Architecture",
            TextEditorWithPreview.Layout.SHOW_PREVIEW,
        )
    }

    override fun getEditorTypeId(): String = "citi-ai-architecture-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
