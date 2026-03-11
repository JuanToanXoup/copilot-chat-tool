package com.github.copilotsilent.ui.webview

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.content.ContentFactory
import javax.swing.JLabel

/**
 * Creates the JCEF-based tool window that hosts the React chat UI.
 *
 * Uses JcefBrowserPanel which serves the React app from plugin resources
 * via a custom resource handler (copilot-webview origin).
 */
class CopilotWebToolWindowFactory : ToolWindowFactory, DumbAware {
    private val log = Logger.getInstance(CopilotWebToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        log.info("createToolWindowContent called")
        val content = if (JcefBrowserPanel.isSupported()) {
            val panel = JcefBrowserPanel(toolWindow.disposable)
            val bridge = WebViewBridge(project, panel, toolWindow.disposable)
            panel.onBridgeReady = {
                log.info("onBridgeReady fired — pushing set-mode:app")
                panel.pushData("set-mode", """{"mode":"app"}""")
            }
            bridge.attach()
            log.info("Calling panel.load()")
            // Load AFTER onBridgeReady is set to avoid race condition
            panel.load()

            ContentFactory.getInstance().createContent(panel.component, "", false)
        } else {
            val label = JLabel("JCEF is not supported in this environment. The React UI requires an IDE with embedded Chromium.")
            ContentFactory.getInstance().createContent(label, "", false)
        }
        toolWindow.contentManager.addContent(content)
    }
}
