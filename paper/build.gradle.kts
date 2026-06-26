plugins {
    kotlin("jvm")
    id("io.papermc.paperweight.userdev")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("26.2.build.+")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation(project(":common"))
    implementation(project(":paper-api"))
    compileOnly("org.slf4j:slf4j-api:2.0.16")
}

kotlin {
    jvmToolchain(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion("26.2")
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
