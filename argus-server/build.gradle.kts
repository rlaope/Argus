plugins {
    java
    application
}

dependencies {
    implementation(project(":argus-core"))
    implementation(project(":argus-cli"))
    implementation(project(":argus-frontend"))
    implementation("io.netty:netty-all:${project.findProperty("nettyVersion")}")
    testImplementation("org.junit.jupiter:junit-jupiter:${project.findProperty("junitVersion")}")
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

application {
    mainClass.set("io.argus.server.ArgusServer")
}

tasks.jar {
    dependsOn(":argus-core:jar")
    // The fat-jar embeds the whole runtime classpath (including sibling-project
    // jars such as :argus-diagnostics that arrive transitively via :argus-cli), so
    // declare that classpath as an input. Without this, Gradle 8.14's strict
    // implicit-dependency validation fails the build.
    dependsOn(configurations.runtimeClasspath)

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
