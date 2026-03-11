package com.citi.assist.model

import com.citi.assist.store.SessionStatus
import com.intellij.util.messages.Topic

fun interface ChatStatusListener {
    companion object Companion {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("copilot.silent.chat.status", ChatStatusListener::class.java)
    }

    fun onStatusChanged(sessionId: String, status: SessionStatus)
}
