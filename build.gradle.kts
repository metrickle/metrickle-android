plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
}

// Maven Central (Central Portal). Credentials and the signing key come from ORG_GRADLE_PROJECT_*
// environment variables (see docs/RELEASING.md); without a key, publications are left unsigned so
// publishToMavenLocal works for checks.
subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
        }
    }
}

allprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String
}
