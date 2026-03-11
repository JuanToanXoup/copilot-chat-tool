plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradleup.shadow")
    application
}

group = "com.citi.assist"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.45.3.0") {
        exclude(group = "org.slf4j")
    }
    implementation("com.microsoft.onnxruntime:onnxruntime:1.17.3") {
        exclude(group = "org.slf4j")
        exclude(group = "io.netty")
    }
    implementation("com.google.code.gson:gson:2.10.1")
}

application {
    mainClass.set("com.citi.assist.backend.server.BackendServerKt")
}

tasks.shadowJar {
    archiveBaseName.set("copilot-backend")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
}

// Alias: ./gradlew :shared-backend:fatJar
tasks.register("fatJar") {
    dependsOn(tasks.shadowJar)
}
