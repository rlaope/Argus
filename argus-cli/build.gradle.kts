plugins {
    id("java")
    id("application")
}

application {
    mainClass.set("io.argus.cli.ArgusCli")
}

dependencies {
    implementation(project(":argus-core"))
    implementation("org.jline:jline:3.26.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.argus.cli.ArgusCli"
        attributes["Implementation-Version"] = project.version.toString()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.compilerArgs.remove("--enable-preview")
}

// Fat JAR for standalone execution
tasks.register<Jar>("fatJar") {
    dependsOn(tasks.jar, ":argus-core:jar")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "io.argus.cli.ArgusCli"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}
