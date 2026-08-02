plugins {
    id("cryon.publish")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.xenondevs.xyz/releases") // InvUI
}

dependencies {
    compileOnly(libs.paper.api)
    // InvUI — the menu layer builds on it, and :paper ships it at runtime, so compileOnly here.
    compileOnly(libs.invui)

    implementation(project(":common"))
}
