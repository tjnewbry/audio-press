plugins {
    // Lets Gradle download the JDK requested by jvmToolchain() when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "audio-press"
