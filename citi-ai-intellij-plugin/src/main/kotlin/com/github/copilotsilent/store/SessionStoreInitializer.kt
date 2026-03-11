package com.github.copilotsilent.store

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Initializes [SessionStore] on project open so it starts
 * observing MessageBus events immediately.
 */
class SessionStoreInitializer : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<SessionStore>().init()
    }
}
