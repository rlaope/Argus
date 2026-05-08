plugins {
    `java-library`
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:${project.findProperty("junitVersion")}")
}

tasks.withType<JavaCompile> {
    options.release.set(11)
}
