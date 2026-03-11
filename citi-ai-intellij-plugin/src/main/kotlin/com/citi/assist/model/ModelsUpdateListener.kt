package com.citi.assist.model

import com.github.copilot.chat.conversation.agent.rpc.command.CopilotModel
import com.intellij.util.messages.Topic

fun interface ModelsUpdateListener {
    companion object Companion {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("copilot.silent.models", ModelsUpdateListener::class.java)
    }

    fun onModelsUpdated(models: List<CopilotModel>)
}
