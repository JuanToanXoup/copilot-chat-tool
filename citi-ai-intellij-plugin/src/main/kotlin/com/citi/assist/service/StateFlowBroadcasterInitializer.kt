package com.citi.assist.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class StateFlowBroadcasterInitializer : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<StateFlowBroadcaster>().init()
    }
}
