import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    group = "io.github.tjnewbry.audiopress"
    version = "0.1.0"
}

// Settings shared by every module. Build with JDK 25, but emit Java 17 bytecode so the engine runs on any
// Java 17+ runtime and can later be used from Android. Move this into a convention plugin if it grows.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(25)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjdk-release=17")
                allWarningsAsErrors.set(true)
            }
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
        dependencies {
            add("testImplementation", kotlin("test"))
            add("testImplementation", platform(libs.junit.bom))
            add("testRuntimeOnly", libs.junit.platform.launcher)
        }
    }
}

// Formatting is checked for every module from here, and `./gradlew build` fails on violations.
spotless {
    kotlin {
        target("*/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
