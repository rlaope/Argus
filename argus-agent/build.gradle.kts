plugins {
    java
}

dependencies {
    implementation(project(":argus-core"))
    implementation(project(":argus-server"))
    testImplementation("org.junit.jupiter:junit-jupiter:${project.findProperty("junitVersion")}")
}

tasks.jar {
    dependsOn(":argus-core:jar", ":argus-server:jar")

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
