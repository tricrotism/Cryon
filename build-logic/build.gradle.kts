plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.kotlin)
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
