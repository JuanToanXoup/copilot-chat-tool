package com.github.copilotsilent.semantic

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class IndexManagerInitializer : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<IndexManager>().indexProject()
    }
}
