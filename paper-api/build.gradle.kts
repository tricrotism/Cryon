plugins {
    id("cryon.publish")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.xenondevs.xyz/releases") // InvUI
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.kotlinx.coroutines)
    compileOnly(libs.invui)
    compileOnly(libs.packetevents)

    implementation(project(":common"))
}
