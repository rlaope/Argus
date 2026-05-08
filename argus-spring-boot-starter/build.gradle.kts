plugins {
    `java-library`
}

dependencies {
    api(project(":argus-core"))
    api(project(":argus-agent"))
    api(project(":argus-server"))
    api(project(":argus-micrometer"))

    compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.2.0")
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure:3.2.0")
    compileOnly("org.springframework:spring-context:6.1.0")
    compileOnly("io.micrometer:micrometer-core:1.12.0")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.2.0")

    testImplementation("org.junit.jupiter:junit-jupiter:${property("junitVersion")}")
    testImplementation("org.springframework.boot:spring-boot-test:3.2.0")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure:3.2.0")
    testImplementation("org.springframework:spring-test:6.1.0")
    testImplementation("org.assertj:assertj-core:3.25.1")
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}
