plugins {
    java
}

// argus-instrument is the OPT-IN, default-OFF live-instrumentation module.
//
// It is loaded ONLY as a dynamic-attach agent into a user-selected target JVM,
// and only when the operator explicitly enables it (`--enable-instrument` /
// `argus.instrument.enabled=true`). No other Argus module declares a compile
// dependency on it: the CLI side talks to the attached agent purely over a
// loopback socket using a newline-delimited JSON contract, so the core
// "non-invasive observability" identity is preserved.
//
// The agent JAR bundles ByteBuddy (manual fat-jar, mirroring argus-agent).
// NOTE (hardening follow-up): relocate `net.bytebuddy` via the Shadow plugin to
// avoid a class clash if the target application itself embeds ByteBuddy.

dependencies {
    implementation("net.bytebuddy:byte-buddy:${project.findProperty("byteBuddyVersion")}")
    // byte-buddy-agent provides ByteBuddyAgent.install() for in-process
    // self-attach so the instrumentation engine can be exercised in unit tests
    // without spawning a separate target JVM.
    testImplementation("net.bytebuddy:byte-buddy-agent:${project.findProperty("byteBuddyVersion")}")
    testImplementation("org.junit.jupiter:junit-jupiter:${project.findProperty("junitVersion")}")
}

// Compile at the JVM 11 baseline: the agent is loaded INTO the target JVM, and
// Argus supports diagnosing JVM 11+. Advice classes inlined into target methods
// must not exceed the target's class-file version, so 11 is the floor.
tasks.withType<JavaCompile> {
    options.release.set(11)
}

// Self-attach in tests needs the JDK attach API to allow attaching to the
// current VM.
tasks.withType<Test> {
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
}

// Self-contained attach-agent JAR. Manifest marks it as an Agent-Class capable
// of class retransformation; the bundled runtime classpath (ByteBuddy) is
// folded in so the single JAR can be handed to `VirtualMachine.loadAgent`.
tasks.jar {
    manifest {
        attributes(
            "Agent-Class" to "io.argus.instrument.AgentMain",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
            "Implementation-Version" to project.version.toString()
        )
    }
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map {
            if (it.isDirectory) it else zipTree(it)
        }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
