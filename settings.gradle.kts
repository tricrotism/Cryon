pluginManagement {
    includeBuild("build-logic")

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "Cryon"

include("common", "paper-api", "paper", "velocity-api", "velocity", "geyser-api", "geyser")
