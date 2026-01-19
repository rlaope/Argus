plugins {
    java
}

dependencies {
    implementation(project(":argus-core"))
    testImplementation("org.junit.jupiter:junit-jupiter:${project.findProperty("junitVersion")}")
}

tasks.jar {
    dependsOn(":argus-core:jar")

    manifest {
        attributes(
            "Premain-Class" to "io.argus.agent.ArgusAgent",
            "Agent-Class" to "io.argus.agent.ArgusAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }

    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map {
            if (it.isDirectory) it else zipTree(it)
        }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
