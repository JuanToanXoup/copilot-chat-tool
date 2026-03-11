plugins {
    id("com.github.node-gradle.node")
}

node {
    download = true
    version = "22.16.0"
    nodeProjectDir = projectDir
}

val backendJar = project(":shared-backend").tasks.named("shadowJar")

// Install npm dependencies (uses workspace hoisting from root package.json)
val npmInstallExt by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    workingDir = rootProject.projectDir   // run from workspace root
    args = listOf("install")
}

// Build the VS Code extension TypeScript → dist/extension.js
val buildExtension by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(npmInstallExt)
    workingDir = projectDir
    args = listOf("run", "build")
}

// Copy the shared-backend fat JAR into ext/ for bundling in the .vsix
val copyBackendJar by tasks.registering(Copy::class) {
    dependsOn(backendJar)
    from(backendJar.map { it.outputs.files })
    into(file("ext"))
}

// Package the .vsix using vsce
val packageVsix by tasks.registering(com.github.gradle.node.npm.task.NpxTask::class) {
    dependsOn(buildExtension, copyBackendJar)
    workingDir = projectDir
    command = "vsce"
    args = listOf("package", "--no-dependencies")
}

// Convenience: ./gradlew :vscode-extension:build
tasks.register("build") {
    dependsOn(packageVsix)
    group = "build"
    description = "Build VS Code extension .vsix (compiles TS + bundles backend JAR)"
}

// Clean task
tasks.register<Delete>("clean") {
    delete("dist", "ext", "*.vsix")
}
