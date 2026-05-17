plugins {
    `java-library`
}

dependencies {
    api(project(":argus-core"))

    testImplementation("org.junit.jupiter:junit-jupiter:${property("junitVersion")}")
}

tasks.withType<JavaCompile> {
    options.release.set(11)
    options.compilerArgs.remove("--enable-preview")
}
