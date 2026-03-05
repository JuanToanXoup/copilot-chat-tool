plugins {
    id("org.jetbrains.intellij") version "1.17.4"
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
}

group = "com.github.copilotsilent"
version = "0.1.0"

repositories {
    mavenCentral()
}

val copilotPluginDir = file("../github-copilot-intellij-1.5.65-243")

intellij {
    version.set("2024.3")
    type.set("IC")
    // No marketplace plugin reference — we add jars manually
}

dependencies {
    // Compile against the Copilot plugin jars
    compileOnly(fileTree(copilotPluginDir.resolve("lib")) {
        include("*.jar")
    })
}

kotlin {
    jvmToolchain(17)
}

val buildWebview = tasks.register<Exec>("buildWebview") {
    description = "Builds the React webview UI into src/main/resources/webview/"
    workingDir = file("webview")
    if (System.getProperty("os.name").lowercase().contains("win")) {
        commandLine("cmd", "/c", "npm", "run", "build")
    } else {
        commandLine("/bin/sh", "-c", "npm run build")
    }
    inputs.dir("webview/src")
    inputs.file("webview/package.json")
    inputs.file("webview/vite.config.ts")
    inputs.file("webview/index.html")
    outputs.file("src/main/resources/webview/index.html")
    // Skip if npm not installed or webview dir doesn't exist
    isIgnoreExitValue = true
}

tasks {
    processResources {
        dependsOn(buildWebview)
    }

    patchPluginXml {
        // Compatible with any IDE version that has the Copilot plugin installed
        sinceBuild.set("233")
        untilBuild.set("")
    }

    buildSearchableOptions {
        enabled = false
    }

    // Include copilot jars on the test classpath too
    test {
        classpath += fileTree(copilotPluginDir.resolve("lib")) {
            include("*.jar")
        }
    }
}
