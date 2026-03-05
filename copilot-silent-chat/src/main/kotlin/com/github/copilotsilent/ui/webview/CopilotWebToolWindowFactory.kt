package com.github.copilotsilent.ui.webview

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.swing.JLabel

/**
 * Creates the JCEF-based tool window that hosts the React chat UI.
 *
 * Uses JcefBrowserPanel which serves the React app from plugin resources
 * via a custom resource handler (copilot-webview origin).
 */
class CopilotWebToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = if (JcefBrowserPanel.isSupported()) {
            val panel = JcefBrowserPanel(toolWindow.disposable)

            // Create a coroutine scope for StateFlow collection, cancelled on dispose
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            Disposer.register(toolWindow.disposable) { scope.cancel() }

            val bridge = WebViewBridge(project, panel, scope)
            bridge.attach()

            // Detach bridge on dispose to cancel flow collectors
            Disposer.register(toolWindow.disposable) { bridge.detach() }

            ContentFactory.getInstance().createContent(panel.component, "", false)
        } else {
            val label = JLabel("JCEF is not supported in this environment. The React UI requires an IDE with embedded Chromium.")
            ContentFactory.getInstance().createContent(label, "", false)
        }
        toolWindow.contentManager.addContent(content)
    }
}
