plugins {
    id("cryon.kotlin")
    id("io.papermc.paperweight.userdev")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/") // PlaceholderAPI
    maven("https://repo.xenondevs.xyz/releases") // InvUI — not published to Maven Central
    maven("https://repo.opencollab.dev/main/") // Floodgate / Cumulus
    maven("https://repo.codemc.io/repository/maven-releases/") // PacketEvents
}

dependencies {
    compileOnly(libs.slf4j)
    compileOnly(libs.placeholderapi)

    implementation(project(":common"))
    implementation(project(":paper-api"))

    // InvUI — the menu framework, shaded UNRELOCATED so module classloaders resolve
    // xyz.xenondevs.invui.* through this jar, exactly like kotlin-stdlib.
    //
    // 2.x is a single mojang-mapped jar with no per-version NMS bridge, so unlike the 1.x line it
    // needs no relocation and no bridge selection. Verified against the 26.2 dev bundle: zero
    // versioned-CraftBukkit references, and every NMS member it touches resolves.
    implementation(libs.invui)

    // Floodgate — Bedrock detection and Cumulus forms. compileOnly and confined to the BedrockService
    // impl, which is only classloaded when the plugin is actually installed.
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
