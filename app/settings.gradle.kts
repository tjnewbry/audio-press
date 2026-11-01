pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com.android.*")
                includeGroupByRegex("com.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download the JDK requested by the engine's jvmToolchain() when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com.android.*")
                includeGroupByRegex("com.google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "audio-press-app"

// The engine is a separate build; Gradle substitutes its :core project for the coordinates used below.
includeBuild("../engine")

// audiopress: the Android application. Named for the product so the path is app/audiopress, not app/app.
include(":audiopress")

// pdf-source: reads PDFs on Android (the engine's TextSource), owned by the engine owner.
include(":pdf-source")

// bake: turns utterances into audio files with TextToSpeech (the Bake feature), owned by the engine owner for now.
include(":bake")
