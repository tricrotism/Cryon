plugins {
    `kotlin-dsl`
}

// The ABI validation DSL that cryon.publish uses is experimental in Kotlin 2.4, and an experimental
// Gradle DSL is an error rather than a warning without this.
kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation")
    }
}

dependencies {
    implementation(libs.gradle.kotlin)
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
