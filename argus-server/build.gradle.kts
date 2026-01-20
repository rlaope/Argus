plugins {
    java
    application
}

dependencies {
    implementation(project(":argus-core"))
    implementation(project(":argus-frontend"))
    implementation("io.netty:netty-all:${project.findProperty("nettyVersion")}")
    testImplementation("org.junit.jupiter:junit-jupiter:${project.findProperty("junitVersion")}")
}

application {
    mainClass.set("io.argus.server.ArgusServer")
}

tasks.jar {
    dependsOn(":argus-core:jar")

    manifest {
        attributes("Main-Class" to "io.argus.server.ArgusServer")
    }

    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map {
            if (it.isDirectory) it else zipTree(it)
        }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
