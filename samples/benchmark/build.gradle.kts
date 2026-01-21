plugins {
    java
    application
}

group = "io.argus.benchmark"
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
    mainClass.set("io.argus.benchmark.OverheadBenchmark")
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

// Baseline run (no Argus)
tasks.register<JavaExec>("runBaseline") {
    group = "benchmark"
    description = "Run benchmark without Argus (baseline)"

    mainClass.set("io.argus.benchmark.OverheadBenchmark")
    classpath = sourceSets["main"].runtimeClasspath

    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    jvmArgs(
        "--enable-preview",
        "-Xms512m",
        "-Xmx512m"
    )

    systemProperty("benchmark.mode", "baseline")
}

// Run with Argus agent only (no server)
tasks.register<JavaExec>("runWithArgusAgent") {
    group = "benchmark"
    description = "Run benchmark with Argus agent (no server)"

    mainClass.set("io.argus.benchmark.OverheadBenchmark")
    classpath = sourceSets["main"].runtimeClasspath

    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    val agentVersion = rootProject.property("argusVersion")
    jvmArgs(
        "--enable-preview",
        "-Xms512m",
        "-Xmx512m",
        "-javaagent:${rootProject.projectDir}/argus-agent/build/libs/argus-agent-${agentVersion}.jar"
    )

    systemProperty("benchmark.mode", "argus-agent")
}

// Run with Argus agent + server
tasks.register<JavaExec>("runWithArgusServer") {
    group = "benchmark"
    description = "Run benchmark with Argus agent + WebSocket server"

    mainClass.set("io.argus.benchmark.OverheadBenchmark")
    classpath = sourceSets["main"].runtimeClasspath

    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    val agentVersion = rootProject.property("argusVersion")
    jvmArgs(
        "--enable-preview",
        "-Xms512m",
        "-Xmx512m",
        "-javaagent:${rootProject.projectDir}/argus-agent/build/libs/argus-agent-${agentVersion}.jar",
        "-Dargus.server.enabled=true",
        "-Dargus.server.port=9202"
    )

    systemProperty("benchmark.mode", "argus-server")
}
