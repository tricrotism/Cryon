plugins {
    `maven-publish`
}

// Env-first with no default, the same rule the runtime config follows. An unset URL declares no
// remote repository at all, so `publish` does nothing rather than pushing somewhere unintended;
// publishToMavenLocal works either way. Credentials are env-only and never live in a file.
val repositoryUrl = providers.environmentVariable("CRYON_PUBLISH_URL")
    .orElse(providers.gradleProperty("cryonPublishUrl"))
val repositoryUsername = providers.environmentVariable("CRYON_PUBLISH_USERNAME")
val repositoryPassword = providers.environmentVariable("CRYON_PUBLISH_PASSWORD")

publishing {
    repositories {
        if (repositoryUrl.isPresent) {
            maven {
                name = "cryon"
                url = uri(repositoryUrl.get())

                // No username resolved means no credentials block at all, the rule RemoteModules
                // follows on the fetching side: an anonymous repository works and a private one
                // rejects the push honestly, rather than Gradle refusing to run over a value the
                // destination never wanted.
                if (repositoryUsername.isPresent) {
                    credentials {
                        username = repositoryUsername.get()
                        password = repositoryPassword.orNull
                    }
                }
            }
        }
    }
}
