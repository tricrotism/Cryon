plugins {
    id("cryon.publish-base")
}

// A loader publishes its shaded jar and nothing else. Its dependencies are inside the jar, so a POM
// listing them would have a consumer resolve a second copy of every shaded class. Nothing compiles
// against these; they are published so a deployment can pull a built loader instead of building it.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "cryon-${project.name}"
            artifact(tasks.named("shadowJar"))
        }
    }
}
