package com.citi.assist.model

import com.intellij.util.messages.Topic

/**
 * Fired by the ArchitecturePreviewEditor when the user clicks a node in the graph.
 * The tool window's WebViewBridge subscribes to this topic to show the node detail panel.
 */
fun interface ArchitectureNodeDetailListener {
    companion object Companion {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("copilot.silent.architecture.nodeDetail", ArchitectureNodeDetailListener::class.java)
    }

    fun onNodeDetail(nodeDetailJson: String)
}
