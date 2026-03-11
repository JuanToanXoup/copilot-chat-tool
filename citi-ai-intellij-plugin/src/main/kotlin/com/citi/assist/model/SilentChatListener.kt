package com.citi.assist.model

import com.intellij.util.messages.Topic

interface SilentChatListener {
    companion object Companion {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("copilot.silent.chat", SilentChatListener::class.java)
    }

    fun onEvent(sessionId: String, event: SilentChatEvent)
}
