plugins {
    id("cryon.publish")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.kotlinx.coroutines)
    compileOnly(libs.velocity.api)

    implementation(project(":common"))
}
