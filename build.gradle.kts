// Root build: declares plugin versions once so subprojects apply them without versions.
// group/version come from gradle.properties and apply to every project.
plugins {
    kotlin("jvm") version "2.4.20-Beta1" apply false
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
    id("com.gradleup.shadow") version "9.4.2" apply false
    id("xyz.jpenilla.run-paper") version "3.0.2" apply false
}
