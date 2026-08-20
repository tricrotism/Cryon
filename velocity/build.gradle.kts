plugins {
    id("cryon.kotlin")
    id("com.gradleup.shadow")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
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
        relocate("org.yaml.snakeyaml", "com.tricrotism.cryon.velocity.libs.snakeyaml")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("velocity-plugin.json") {
            expand(props)
        }
    }
}
