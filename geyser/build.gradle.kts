plugins {
    id("cryon.kotlin")
    id("com.gradleup.shadow")
    id("cryon.publish-shaded")
}

repositories {
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    // Keeps the shipped config.yml and the declared ConfigKeys agreeing; see PaperConfigDriftTest.
    testImplementation(kotlin("test"))
    testImplementation(libs.snakeyaml)

    compileOnly(libs.geyser.api)
    compileOnly(libs.slf4j)
    compileOnly(libs.bundles.adventure)

    // Geyser bundles adventure-api, slf4j-api and snakeyaml, but not MiniMessage, which Mini needs.
    implementation(libs.adventure.minimessage)
    implementation(libs.lettuce)
    implementation(libs.caffeine)
    implementation(libs.snakeyaml)
    implementation(libs.bundles.sql)
    implementation(libs.kotlinx.coroutines)
    implementation(project(":common"))
    implementation(project(":geyser-api"))
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        // Named for the extensions folder it lands in, not for the Gradle module it came from.
        archiveBaseName.set("Cryon-Geyser")
        archiveClassifier.set("")

        relocate("org.yaml.snakeyaml", "com.tricrotism.cryon.geyser.libs.snakeyaml")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("extension.yml") {
            expand(props)
        }
    }
}
