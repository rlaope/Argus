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
    mainClass.set("io.argus.sample.ThreadSimulation")
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

// Run simulation with Argus agent + server
tasks.register<JavaExec>("runSimulation") {
    group = "application"
    description = "Run 10 virtual threads simulation with Argus monitoring"

    mainClass.set("io.argus.sample.ThreadSimulation")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`

    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    jvmArgs(
        "--enable-preview",
        "-javaagent:${rootProject.projectDir}/argus-agent/build/libs/argus-agent-${rootProject.property("argusVersion")}.jar",
        "-Dargus.server.enabled=true",
        "-Dargus.server.port=9202",
        "-Dduration=${System.getProperty("duration") ?: "300"}"
    )
}

// Run metrics demo with GC/CPU activity
tasks.register<JavaExec>("runMetricsDemo") {
    group = "application"
    description = "Run metrics demo with GC, CPU, and virtual thread activity"

    mainClass.set("io.argus.sample.MetricsDemo")
    classpath = sourceSets["main"].runtimeClasspath

    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    jvmArgs(
        "--enable-preview",
        "-Xmx512m",  // Enough heap for JFR + Netty + app
        "-Xms256m",
        "-XX:+UseG1GC",
        "-javaagent:${rootProject.projectDir}/argus-agent/build/libs/argus-agent-${rootProject.property("argusVersion")}.jar",
        "-Dargus.server.enabled=true",
        "-Dargus.server.port=9202",
        "-Dargus.gc.enabled=true",
        "-Dargus.cpu.enabled=true",
        "-Dduration=${System.getProperty("duration") ?: "300"}"
    )
}

// Run metrics demo with ALL features enabled (including high-overhead ones)
tasks.register<JavaExec>("runMetricsDemoFull") {
    group = "application"
    description = "Run metrics demo with ALL profiling features enabled (high overhead)"

    mainClass.set("io.argus.sample.MetricsDemo")
    classpath = sourceSets["main"].runtimeClasspath

    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    jvmArgs(
        "--enable-preview",
        "-Xmx1g",  // More heap for full profiling
        "-Xms512m",
        "-XX:+UseG1GC",
        "-javaagent:${rootProject.projectDir}/argus-agent/build/libs/argus-agent-${rootProject.property("argusVersion")}.jar",
        "-Dargus.server.enabled=true",
        "-Dargus.server.port=9202",
        // Core features
        "-Dargus.gc.enabled=true",
        "-Dargus.cpu.enabled=true",
        "-Dargus.metaspace.enabled=true",
        // High-overhead features (opt-in)
        "-Dargus.allocation.enabled=true",
        "-Dargus.allocation.threshold=1048576",  // 1MB threshold
        "-Dargus.profiling.enabled=true",
        "-Dargus.profiling.interval=50",  // 50ms interval (lower overhead)
        "-Dargus.contention.enabled=true",
        "-Dargus.contention.threshold=20",  // 20ms threshold
        "-Dargus.correlation.enabled=true",
        // Default: 5 minutes (override with -Dduration=N)
        "-Dduration=${System.getProperty("duration") ?: "300"}"
    )
}
