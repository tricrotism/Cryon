plugins {
    kotlin("jvm")
    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    compileOnly("org.slf4j:slf4j-api:2.0.16")
    compileOnly("net.kyori:adventure-api:4.17.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.17.0")
    compileOnly("net.kyori:adventure-text-serializer-legacy:4.17.0")
    compileOnly("com.github.ben-manes.caffeine:caffeine:3.1.8") // Cache; provided by the platform at runtime
    // Cross-server infra; provided at runtime by the core via Paper's plugin.yml `libraries:` loader.
    compileOnly("com.zaxxer:HikariCP:5.1.0")
    compileOnly("org.postgresql:postgresql:42.7.4")
    compileOnly("io.lettuce:lettuce-core:6.4.0.RELEASE")
}

kotlin {
    jvmToolchain(25)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
