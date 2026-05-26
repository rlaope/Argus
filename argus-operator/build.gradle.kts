plugins {
    java
    application
}

val fabric8Version = "6.13.4"
val jacksonVersion = "2.17.2"
val slf4jVersion = "2.0.13"

dependencies {
    implementation("io.fabric8:kubernetes-client:$fabric8Version")
    implementation("io.fabric8:generator-annotations:$fabric8Version")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    runtimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")

    annotationProcessor("io.fabric8:crd-generator-apt:$fabric8Version")

    testImplementation("org.junit.jupiter:junit-jupiter:${project.findProperty("junitVersion")}")
    testImplementation("io.fabric8:kubernetes-server-mock:$fabric8Version")
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

application {
    mainClass.set("io.argus.operator.ArgusOperator")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "io.argus.operator.ArgusOperator",
            "Implementation-Title" to "argus-operator",
            "Implementation-Version" to project.version.toString()
        )
    }
}
