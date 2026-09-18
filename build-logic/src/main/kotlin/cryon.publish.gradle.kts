plugins {
    id("cryon.kotlin")
    id("cryon.publish-base")
}

// ABI validation rides on publishing, because "something else compiles against this" is exactly what
// publishing means here. The three loaders publish a shaded jar with an empty POM through
// cryon.publish-shaded instead, so they are excluded without anyone keeping a list.
//
// Feature jars are built separately against these coordinates and loaded into their own classloaders,
// so a changed signature is not caught by compiling this repo: it surfaces as a NoSuchMethodError in
// somebody's deployed jar, at the call rather than at load. The dump makes it a reviewable diff.
// JetBrains' standalone binary-compatibility-validator cannot be used here: its ASM rejects Java 25
// bytecode ("Unsupported class file major version 69") up to and including 0.18.2.
// Declaring the block is what enables it; the `enabled` property was removed in 2.4.20-Beta1.
kotlin {
    abiValidation {}
}

// The artifactId is the bare project name (common, paper-api, …) because feature repos already
// compile against those coordinates. Renaming them would break every module repo at once.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
