package com.github.copilotsilent.model

import com.github.copilotsilent.store.SessionStatus
import com.intellij.util.messages.Topic

fun interface ChatStatusListener {
    companion object Companion {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("copilot.silent.chat.status", ChatStatusListener::class.java)
    }

    fun onStatusChanged(sessionId: String, status: SessionStatus)
}
