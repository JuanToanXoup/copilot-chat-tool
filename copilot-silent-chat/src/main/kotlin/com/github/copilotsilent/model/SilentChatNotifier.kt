package com.github.copilotsilent.model

import com.intellij.util.messages.Topic

interface SilentChatNotifier {
    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("copilot.silent.chat", SilentChatNotifier::class.java)
    }

    fun onEvent(sessionId: String, event: SilentChatEvent)
}
