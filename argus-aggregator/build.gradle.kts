plugins {
    java
    application
}

dependencies {
    implementation(project(":argus-core"))
    implementation("io.netty:netty-all:${project.findProperty("nettyVersion")}")
    testImplementation("org.junit.jupiter:junit-jupiter:${project.findProperty("junitVersion")}")
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

application {
    mainClass.set("io.argus.aggregator.ArgusAggregator")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "io.argus.aggregator.ArgusAggregator")
        attributes("Implementation-Version" to project.version.toString())
    }
}

tasks.register<Jar>("fatJar") {
    dependsOn(tasks.jar, ":argus-core:jar")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "io.argus.aggregator.ArgusAggregator")
        attributes("Implementation-Version" to project.version.toString())
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}
