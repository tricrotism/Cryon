plugins {
    id("cryon.kotlin")
    id("io.papermc.paperweight.userdev")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.xenondevs.xyz/releases")
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly(libs.slf4j)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.caffeine)

    implementation(project(":common"))
    implementation(project(":paper-api"))
    implementation(libs.invui)
    compileOnly(libs.floodgate)

    implementation(libs.packetevents)

    paperweight.paperDevBundle(libs.versions.paperDevBundle.get())
}

runPaper.folia.registerTask()

tasks {
    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    named<xyz.jpenilla.runpaper.task.RunServer>("runFolia") {
        minecraftVersion(libs.versions.minecraft.get())
        runDirectory.set(layout.projectDirectory.dir("run-folia"))
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
