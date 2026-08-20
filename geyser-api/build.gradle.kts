plugins {
    id("cryon.publish")
}

repositories {
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    compileOnly(libs.slf4j)
    compileOnly(libs.kotlinx.coroutines)
    compileOnly(libs.geyser.api)
    compileOnly(libs.bundles.adventure)

    implementation(project(":common"))
}
