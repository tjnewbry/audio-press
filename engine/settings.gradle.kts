plugins {
    // Lets Gradle download the JDK requested by jvmToolchain() when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "audio-press-engine"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// core: the engine. pdfbox: reads PDFs for core. cli: the command-line program.
include(":core", ":pdfbox", ":cli")
