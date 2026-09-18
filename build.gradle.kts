plugins {
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.runPaper) apply false
    alias(libs.plugins.paperweight) apply false
}

// Every subproject publishes, so these depend on all of them by path rather than on a hand-kept
// list. A module added to settings.gradle.kts is picked up without anyone remembering to add it
// here, which is the whole point: the failure mode of the old hand-listed command was an artifact
// that silently never shipped.
tasks.register("publishAll") {
    group = "publishing"
    description = "Publishes every module and API to the configured Maven repository."
    dependsOn(subprojects.map { "${it.path}:publish" })
}

tasks.register("publishAllToMavenLocal") {
    group = "publishing"
    description = "Publishes every module and API to the local Maven repository."
    dependsOn(subprojects.map { "${it.path}:publishToMavenLocal" })
}
