package com.citi.assist.model

import com.intellij.util.messages.Topic

/**
 * Fired by the PlaybookPreviewEditor when the user clicks a step node in the DAG.
 * The tool window's WebViewBridge subscribes to this topic to show the step detail panel.
 */
fun interface PlaybookStepDetailListener {
    companion object Companion {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("copilot.silent.playbook.stepDetail", PlaybookStepDetailListener::class.java)
    }

    fun onStepDetail(stepDetailJson: String)
}
