plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "argus"

include("argus-core")
include("argus-agent")
include("argus-server")
include("argus-frontend")
include("argus-cli")
include("argus-diagnostics")
include("argus-micrometer")
include("argus-spring-boot-starter")
include("argus-aggregator")
include("argus-operator")
include("argus-instrument")

// Sample projects
include("samples:virtual-thread-demo")
include("samples:virtual-thread-simulation")
include("samples:benchmark")
