plugins {
    id("cryon.kotlin")
    id("com.gradleup.shadow")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.velocity.api)

    implementation(libs.lettuce)
    implementation(libs.caffeine)
    implementation(libs.snakeyaml)
    implementation(libs.bundles.sql)
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
