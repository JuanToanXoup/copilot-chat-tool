import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.zip.ZipFile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij") version "1.17.4"
    id("com.github.node-gradle.node") version "7.1.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

/**
 * Finds an optional plugin's JARs from any local IDE installation (ignores build version).
 * Returns the list of JAR files, or empty if the plugin isn't installed anywhere.
 * These are added as compileOnly deps — at runtime the IDE's classloader provides them.
 */
fun findOptionalPluginJars(dirName: String): List<File> {
    val dirs = findLocalPluginDirs(dirName)
    if (dirs.isEmpty()) {
        logger.lifecycle("Optional plugin '$dirName' not found in any local IDE — Tier 1 compilation will be skipped")
        return emptyList()
    }
    val pluginDir = dirs.first()
    val jars = file("${pluginDir.absolutePath}/lib").listFiles()
        ?.filter { it.name.endsWith(".jar") }
        ?: emptyList()
    logger.lifecycle("Using $dirName JARs (compile-only) from $pluginDir")
    return jars
}

val gherkinJars = findOptionalPluginJars("gherkin")
val cucumberJavaJars = findOptionalPluginJars("cucumber-java")
val hasGherkinCompileDeps = gherkinJars.isNotEmpty() && cucumberJavaJars.isNotEmpty()

dependencies {
    compileOnly("com.google.code.gson:gson:2.10.1")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0") {
        // Exclude SLF4J — IntelliJ provides its own, and having two causes LinkageError
        exclude(group = "org.slf4j")
    }
    implementation("com.microsoft.onnxruntime:onnxruntime:1.17.3") {
        exclude(group = "org.slf4j")
        exclude(group = "io.netty")
    }
    val exposedVersion = "0.58.0"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion") {
        exclude(group = "org.slf4j")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion") {
        exclude(group = "org.slf4j")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    // Gherkin/Cucumber JARs from local IDE — compile-only, runtime provided by IDE classloader
    if (hasGherkinCompileDeps) {
        gherkinJars.forEach { compileOnly(files(it)) }
        cucumberJavaJars.forEach { compileOnly(files(it)) }
    }
}

/**
 * Reads the since-build prefix (e.g. "243") from a plugin JAR's META-INF/plugin.xml.
 */
fun readPluginBuildPrefix(pluginDir: File): String? {
    val libDir = file("${pluginDir.absolutePath}/lib")
    val regex = Regex("""since-build="(\d+)""")
    for (jar in (libDir.listFiles() ?: emptyArray())) {
        if (!jar.name.endsWith(".jar")) continue
        try {
            val zipFile = ZipFile(jar)
            val entry = zipFile.getEntry("META-INF/plugin.xml")
            if (entry != null) {
                val xml = zipFile.getInputStream(entry).bufferedReader().readText()
                zipFile.close()
                val match = regex.find(xml)
                if (match != null) return match.groupValues[1]
            } else {
                zipFile.close()
            }
        } catch (_: Exception) { }
    }
    return null
}

/**
 * Scans local JetBrains IDE installations and returns every plugin directory
 * matching [pluginDirName] whose since-build prefix matches [buildPrefix] (e.g. "243").
 * Results are sorted newest-IDE-first so callers can pick the best match.
 */
fun findLocalPluginDirs(pluginDirName: String, buildPrefix: String? = null): List<File> {
    val home = System.getProperty("user.home")
    val bases = listOf(
        "$home/Library/Application Support/JetBrains",   // macOS
        "$home/.local/share/JetBrains",                   // Linux
        "${System.getenv("APPDATA") ?: "$home/AppData/Roaming"}/JetBrains", // Windows
    )
    val idePatterns = listOf("IntelliJIdea*", "IdeaIC*")

    return bases.asSequence()
        .map { file(it) }
        .filter { it.isDirectory }
        .flatMap { baseDir ->
            idePatterns.asSequence().flatMap { pattern ->
                (baseDir.listFiles { f ->
                    f.isDirectory && f.name.matches(Regex(pattern.replace("*", ".*")))
                } ?: emptyArray()).asSequence()
            }
        }
        .sortedByDescending { it.name }
        .mapNotNull { ideDir ->
            val pluginDir = file("${ideDir.absolutePath}/plugins/$pluginDirName")
            if (pluginDir.isDirectory && file("${pluginDir.absolutePath}/lib").isDirectory)
                pluginDir
            else null
        }
        .filter { pluginDir ->
            if (buildPrefix == null) return@filter true
            val prefix = readPluginBuildPrefix(pluginDir)
            prefix != null && prefix == buildPrefix
        }
        .toList()
}

fun findCopilotPlugin(): String {
    val explicit = providers.gradleProperty("copilotPluginPath").orNull
    if (explicit != null && file(explicit).exists()) return explicit

    val versionRegex = Regex("""github-copilot-intellij-(\d+\.\d+\.\d+)-\d+\.jar""")

    data class CopilotCandidate(val dir: String, val version: List<Int>)

    val found = findLocalPluginDirs("github-copilot-intellij").mapNotNull { pluginDir ->
        val libDir = file("${pluginDir.absolutePath}/lib")
        val versionParts =
            libDir.listFiles()?.firstNotNullOfOrNull { jar -> versionRegex.find(jar.name)?.groupValues?.get(1) }
                ?.split(".")
                ?.map { it.toIntOrNull() ?: 0 }
                ?: return@mapNotNull null
        CopilotCandidate(pluginDir.absolutePath, versionParts)
    }

    if (found.isNotEmpty()) {
        val best = found.sortedWith(
            compareByDescending<CopilotCandidate> { it.version.getOrElse(0) { 0 } }
                .thenByDescending { it.version.getOrElse(1) { 0 } }
                .thenByDescending { it.version.getOrElse(2) { 0 } }
        ).first()
        logger.lifecycle("Using Copilot plugin ${best.version.joinToString(".")} from ${best.dir}")
        return best.dir
    }

    error("""
        Cannot find GitHub Copilot plugin. Set copilotPluginPath in one of:
          - gradle.properties  (copilotPluginPath=/path/to/github-copilot-intellij)
          - command line: ./gradlew build -PcopilotPluginPath=/path/to/github-copilot-intellij
    """.trimIndent())
}

node {
    download = true
    version = "22.16.0"           // LTS — auto-downloaded, no system PATH dependency
    nodeProjectDir = file("webview")
}

intellij {
    pluginName.set(providers.gradleProperty("pluginName"))
    version.set(providers.gradleProperty("platformVersion"))
    type.set(providers.gradleProperty("platformType"))

    plugins.set(listOf(
        findCopilotPlugin(),
        "com.intellij.java",
        "org.jetbrains.plugins.gradle",
        "org.jetbrains.idea.maven",
    ))
}

// Exclude GherkinPluginResolver from compilation when Gherkin JARs aren't available
if (!hasGherkinCompileDeps) {
    sourceSets.main {
        kotlin.exclude("**/gherkin/GherkinPluginResolver.kt")
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    patchPluginXml {
        version.set(providers.gradleProperty("pluginVersion"))
        sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
        untilBuild.set("")
    }

    buildSearchableOptions {
        enabled = false
    }

    val npmInstallWebview by registering(com.github.gradle.node.npm.task.NpmTask::class) {
        workingDir = file("webview")
        args = listOf("install")
    }

    val buildWebview by registering(com.github.gradle.node.npm.task.NpmTask::class) {
        dependsOn(npmInstallWebview)
        workingDir = file("webview")
        args = listOf("run", "build")
    }

    val copyWebview by registering(Copy::class) {
        dependsOn(buildWebview)
        from("webview/dist")
        into("src/main/resources/webview")
    }

    processResources { dependsOn(copyWebview) }

    named<org.jetbrains.intellij.tasks.RunIdeTask>("runIde") {
    }

    register("installLocal") {
        group = "intellij"
        description = "Build plugin and install via IDE REST API (PluginInstaller.unpackPlugin)"
        dependsOn("buildPlugin")

        val pluginName = providers.gradleProperty("pluginName").get()
        val pluginVersion = providers.gradleProperty("pluginVersion").get()
        val zipPath = layout.buildDirectory.file("distributions/$pluginName-$pluginVersion.zip")

        doLast {
            val zipFile = zipPath.get().asFile
            if (!zipFile.exists()) error("Plugin ZIP not found: $zipFile")

            val encodedPath = URLEncoder.encode(zipFile.absolutePath, "UTF-8")
            try {
                val url = URI("http://localhost:63342/api/agent-tools/install?path=$encodedPath").toURL()
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    logger.lifecycle("Plugin installed and IDE restart triggered.")
                } else {
                    logger.warn("IDE returned HTTP $code — install and restart manually.")
                }
            } catch (e: java.net.ConnectException) {
                logger.warn("IDE not running — falling back to manual extraction.")
                val targetDir = file("${System.getProperty("user.home")}/Library/Application Support/JetBrains/IdeaIC2025.2/plugins")
                val oldDir = File(targetDir, pluginName)
                if (oldDir.exists()) oldDir.deleteRecursively()
                project.copy {
                    from(project.zipTree(zipFile))
                    into(targetDir)
                }
                logger.lifecycle("Installed $pluginName to $targetDir — start IDE manually.")
            } catch (e: Exception) {
                logger.warn("Failed to trigger install: ${e.message}")
            }
        }
    }
}
