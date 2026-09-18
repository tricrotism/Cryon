plugins {
    id("cryon.kotlin")
    id("io.papermc.paperweight.userdev")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
    id("cryon.publish-shaded")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.xenondevs.xyz/releases")
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // Keeps the shipped config.yml and the declared ConfigKeys agreeing. A key added without a
    // template entry is an option no operator can discover, and nothing at runtime notices.
    testImplementation(kotlin("test"))
    testImplementation(libs.snakeyaml)

    compileOnly(libs.slf4j)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.caffeine)

    implementation(project(":common"))
    implementation(project(":paper-api"))
    implementation(libs.invui)
    compileOnly(libs.floodgate)

    // NOT shaded. The standalone PacketEvents plugin supplies it at runtime and owns its lifecycle;
    // shading it unrelocated meant two copies both injected the pipeline whenever that plugin was
    // also installed, which is the conflict this avoids. Feature jars keep compiling against it
    // exactly as before, resolving through the plugin classloader group rather than through us.
    compileOnly(libs.packetevents)
    implementation(libs.kotlinx.coroutines)

    paperweight.paperDevBundle(libs.versions.paperDevBundle.get())
}

runPaper.folia.registerTask()

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        // Named for the plugins folder it lands in, not for the Gradle module it came from.
        archiveBaseName.set("Cryon-Paper")
        archiveClassifier.set("")

        // Append rather than overwrite same-named META-INF/services files. Nothing bundled today
        // actually ships one, coroutines-core carries its wiring in code rather than as a service, so this
        // changes no current output; it is here so that adding a dependency that *does* use the
        // ServiceLoader cannot silently lose every provider but the last jar merged.
        mergeServiceFiles()
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
