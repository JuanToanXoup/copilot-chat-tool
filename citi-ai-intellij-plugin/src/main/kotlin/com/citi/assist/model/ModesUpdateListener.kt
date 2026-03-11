package com.citi.assist.model

import com.github.copilot.chat.conversation.agent.rpc.command.ChatMode
import com.intellij.util.messages.Topic

fun interface ModesUpdateListener {
    companion object Companion {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("copilot.silent.modes", ModesUpdateListener::class.java)
    }

    fun onModesUpdated(modes: List<ChatMode>, currentMode: ChatMode?)
}
