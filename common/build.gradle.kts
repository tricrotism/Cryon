plugins {
    id("cryon.publish")
}

dependencies {
    compileOnly(libs.slf4j)
    compileOnly(libs.lettuce)
    compileOnly(libs.caffeine)
    compileOnly(libs.hikaricp)
    compileOnly(libs.postgresql)
    compileOnly(libs.bundles.adventure)
}
