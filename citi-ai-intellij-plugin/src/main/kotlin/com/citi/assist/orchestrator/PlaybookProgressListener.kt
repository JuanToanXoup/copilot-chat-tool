package com.citi.assist.orchestrator

import com.intellij.util.messages.Topic

/**
 * Fired by PlaybookExecutor as steps change state during execution.
 * The tool window and file editor subscribe to show live progress.
 */
fun interface PlaybookProgressListener {
    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("copilot.silent.playbook.progress", PlaybookProgressListener::class.java)
    }

    fun onProgress(progressJson: String)
}
