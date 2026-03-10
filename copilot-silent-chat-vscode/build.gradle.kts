plugins {
    id("com.github.node-gradle.node") version "7.1.0"
}

group = "ai.citi.agent_automation"
version = providers.gradleProperty("extensionVersion").get()

node {
    download = true
    version = providers.gradleProperty("nodeVersion").get()
}

// ── Shared webview source from IntelliJ project ──────────────────────────────
// The React webview is maintained in the IntelliJ plugin. The VS Code extension
// only overrides webview/src/bridge.ts. At build time we assemble a merged copy
// under build/webview-merged so Vite can compile it without touching either
// source tree.

val webviewMergedDir = layout.buildDirectory.dir("webview-merged")
val intellijWebviewDir = file("../copilot-silent-chat/webview")

// Vite config uses outDir: '../src/main/resources/webview' relative to the merged
// webview dir, so the built output lands at build/src/main/resources/webview/
val webviewBuildOutput = layout.buildDirectory.dir("src/main/resources/webview")

val mergeWebviewSources by tasks.registering(Copy::class) {
    description = "Merge IntelliJ webview source with VS Code bridge overlay"

    // Base: all IntelliJ webview files, excluding the JCEF bridge that VS Code replaces
    from(intellijWebviewDir) {
        exclude("node_modules", "dist", ".vite", "src/bridge.ts")
    }
    // Overlay: VS Code bridge.ts replaces the IntelliJ JCEF bridge
    from("webview")
    into(webviewMergedDir)
}

// ── npm install (extension root) ─────────────────────────────────────────────

val npmInstallExtension by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    description = "Install extension host dependencies"
    args = listOf("install")

    inputs.file("package.json")
    outputs.dir("node_modules")
}

// ── npm install (merged webview) ─────────────────────────────────────────────

val npmInstallWebview by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(mergeWebviewSources)
    description = "Install webview dependencies in merged directory"

    workingDir = webviewMergedDir.get().asFile
    args = listOf("install")

    inputs.file(webviewMergedDir.map { it.file("package.json") })
    outputs.dir(webviewMergedDir.map { it.dir("node_modules") })
}

// ── Build webview (Vite) ─────────────────────────────────────────────────────

val buildWebview by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(npmInstallWebview)
    description = "Build React webview via Vite (tsc + vite build)"

    workingDir = webviewMergedDir.get().asFile
    args = listOf("run", "build")

    inputs.dir(webviewMergedDir.map { it.dir("src") })
    outputs.dir(webviewBuildOutput)
}

// ── Copy webview output into dist/webview/ ───────────────────────────────────

val copyWebviewDist by tasks.registering(Copy::class) {
    dependsOn(buildWebview)
    description = "Copy built webview assets into dist/webview/"

    from(webviewBuildOutput)
    into(layout.projectDirectory.dir("dist/webview"))
}

// ── Build extension (esbuild) ────────────────────────────────────────────────

val buildExtension by tasks.registering(com.github.gradle.node.npm.task.NpxTask::class) {
    dependsOn(npmInstallExtension)
    description = "Bundle extension host via esbuild"

    command = "esbuild"
    args = listOf(
        "src/extension.ts",
        "--bundle",
        "--outfile=dist/extension.js",
        "--external:vscode",
        "--external:better-sqlite3",
        "--format=cjs",
        "--platform=node",
        "--target=node18"
    )

    inputs.dir("src")
    inputs.file("tsconfig.json")
    outputs.file("dist/extension.js")
}

// ── TypeScript type-check (no emit) ──────────────────────────────────────────

val typeCheck by tasks.registering(com.github.gradle.node.npm.task.NpxTask::class) {
    dependsOn(npmInstallExtension)
    description = "Run TypeScript compiler for type checking only"

    command = "tsc"
    args = listOf("--noEmit")

    inputs.dir("src")
    inputs.file("tsconfig.json")
}

// ── Lint ─────────────────────────────────────────────────────────────────────

val lint by tasks.registering(com.github.gradle.node.npm.task.NpxTask::class) {
    dependsOn(npmInstallExtension)
    description = "Run ESLint on extension source"

    command = "eslint"
    args = listOf("src/")
}

// ── Test ─────────────────────────────────────────────────────────────────────

val test by tasks.registering(com.github.gradle.node.npm.task.NpxTask::class) {
    dependsOn(npmInstallExtension)
    description = "Run vitest"

    command = "vitest"
    args = listOf("run")
}

// ── Package VSIX ─────────────────────────────────────────────────────────────

val packageVsix by tasks.registering(com.github.gradle.node.npm.task.NpxTask::class) {
    dependsOn(buildExtension, copyWebviewDist)
    description = "Package VS Code extension as .vsix"

    command = "vsce"
    args = listOf("package", "--no-dependencies", "--skip-license")

    // Skip vscode:prepublish — Gradle already built everything
    environment.put("VSCE_TESTS", "1")

    outputs.files(fileTree(".") { include("*.vsix") })
}

// ── Aggregate tasks ──────────────────────────────────────────────────────────

val buildPlugin by tasks.registering {
    group = "build"
    description = "Full build: webview + extension + package VSIX"
    dependsOn(buildExtension, copyWebviewDist, packageVsix)
}

tasks.register("build") {
    group = "build"
    description = "Build extension and webview (no VSIX packaging)"
    dependsOn(buildExtension, copyWebviewDist)
}

val compileExtension by tasks.registering {
    group = "build"
    description = "Type-check and bundle extension (no webview)"
    dependsOn(typeCheck, buildExtension)
}

// ── Clean ────────────────────────────────────────────────────────────────────

tasks.register<Delete>("clean") {
    group = "build"
    description = "Delete build outputs"
    delete("dist", layout.buildDirectory, fileTree(".") { include("*.vsix") })
}
