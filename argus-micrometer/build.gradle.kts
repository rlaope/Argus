plugins {
    `java-library`
}

dependencies {
    api(project(":argus-server"))

    compileOnly("io.micrometer:micrometer-core:1.12.0")

    testImplementation("io.micrometer:micrometer-core:1.12.0")
    testImplementation("org.junit.jupiter:junit-jupiter:${property("junitVersion")}")
}
