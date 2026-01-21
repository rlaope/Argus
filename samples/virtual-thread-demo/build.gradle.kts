plugins {
    java
    application
}

group = "io.argus.sample"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("io.argus.sample.VirtualThreadDemo")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("--enable-preview"))
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

// Task to run with Argus agent
tasks.register<JavaExec>("runWithArgus") {
    group = "application"
    description = "Run the demo with Argus agent attached"

    mainClass.set("io.argus.sample.VirtualThreadDemo")
    classpath = sourceSets["main"].runtimeClasspath

    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    jvmArgs(
        "--enable-preview",
        "-javaagent:${rootProject.projectDir}/argus-agent/build/libs/argus-agent-${rootProject.property("argusVersion")}.jar"
    )
}

// Task to run with Argus agent + WebSocket server
tasks.register<JavaExec>("runWithServer") {
    group = "application"
    description = "Run the demo with Argus agent and WebSocket server enabled"

    mainClass.set("io.argus.sample.VirtualThreadDemo")
    classpath = sourceSets["main"].runtimeClasspath

    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    jvmArgs(
        "--enable-preview",
        "-javaagent:${rootProject.projectDir}/argus-agent/build/libs/argus-agent-${rootProject.property("argusVersion")}.jar",
        "-Dargus.server.enabled=true",
        "-Dargus.server.port=9202"
    )
}

// Long-running server demo for WebSocket testing
tasks.register<JavaExec>("runServerDemo") {
    group = "application"
    description = "Run long-running demo with WebSocket server (press Enter to stop)"

    mainClass.set("io.argus.sample.ServerDemo")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`

    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    jvmArgs(
        "--enable-preview",
        "-javaagent:${rootProject.projectDir}/argus-agent/build/libs/argus-agent-${rootProject.property("argusVersion")}.jar",
        "-Dargus.server.enabled=true",
        "-Dargus.server.port=9202"
    )
}
