plugins {
    id("cryon.publish")
}

dependencies {
    compileOnly(libs.slf4j)
    compileOnly(libs.kotlinx.coroutines)
    compileOnly(libs.lettuce)
    compileOnly(libs.caffeine)
    compileOnly(libs.hikaricp)
    compileOnly(libs.postgresql)
    compileOnly(libs.snakeyaml)
    compileOnly(libs.bundles.adventure)

    // The generated config template is the one thing here a running server cannot check: a malformed
    // one ships in the jar and ConfigMigrator leaves an unparseable file exactly as it is, silently.
    testImplementation(kotlin("test"))
    testImplementation(libs.snakeyaml)
    // compileOnly does not reach the test classpath, and SingleFlight is suspending.
    testImplementation(libs.kotlinx.coroutines)
    testImplementation(libs.slf4j)
    // Same reason: CryonPaletteTest parses real MiniMessage, because the failure it guards against is
    // a tag that silently stops resolving and reaches a player as literal text.
    testImplementation(libs.bundles.adventure)
}
