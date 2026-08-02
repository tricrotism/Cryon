plugins {
    id("cryon.publish")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.velocity.api)

    implementation(project(":common"))
}
