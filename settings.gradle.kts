plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "CloudstreamPlugins"

// This file sets what projects are included. All new projects should get automatically included unless specified in "disabled" variable.
//


// To only include a single project, comment out the previous lines (except the first one), and include your plugin like so:
include("PinayCum")