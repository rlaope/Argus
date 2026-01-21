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

    // Pass duration property if provided: -Dduration=10
    val duration = System.getProperty("duration")

    jvmArgs(
        "--enable-preview",
        "-javaagent:${rootProject.projectDir}/argus-agent/build/libs/argus-agent-${rootProject.property("argusVersion")}.jar",
        "-Dargus.server.enabled=true",
        "-Dargus.server.port=9202"
    )

    if (duration != null) {
        jvmArgs("-Dduration=$duration")
    }
}
