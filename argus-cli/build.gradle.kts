plugins {
    id("java")
    id("application")
}

application {
    mainClass.set("io.argus.cli.ArgusTop")
}

dependencies {
    implementation(project(":argus-core"))
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.argus.cli.ArgusTop"
    }
}

// Fat JAR for standalone execution
tasks.register<Jar>("fatJar") {
    dependsOn(tasks.jar, ":argus-core:jar")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "io.argus.cli.ArgusTop"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}
