plugins {
    id("cryon.kotlin")
    id("com.gradleup.shadow")
    id("cryon.publish-shaded")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    // Keeps the shipped config.yml and the declared ConfigKeys agreeing; see PaperConfigDriftTest.
    testImplementation(kotlin("test"))
    testImplementation(libs.snakeyaml)

    compileOnly(libs.velocity.api)
    compileOnly(libs.floodgate)

    implementation(libs.lettuce)
    implementation(libs.caffeine)
    implementation(libs.snakeyaml)
    implementation(libs.bundles.sql)
    implementation(libs.kotlinx.coroutines)
    implementation(project(":common"))
    implementation(project(":velocity-api"))
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        // Named for the plugins folder it lands in, not for the Gradle module it came from.
        archiveBaseName.set("Cryon-Velocity")
        archiveClassifier.set("")

        relocate("org.yaml.snakeyaml", "com.tricrotism.cryon.velocity.libs.snakeyaml")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("velocity-plugin.json") {
            expand(props)
        }
    }
}
