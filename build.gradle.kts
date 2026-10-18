import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spotless)
    application
}

group = "io.github.tjnewbry"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.pdfbox)
    implementation(libs.clikt.core)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Build with JDK 25, but emit Java 17 bytecode so the program runs on any Java 17+ runtime (see docs/architecture.md#design-decisions).
kotlin {
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

application {
    mainClass.set("io.github.tjnewbry.audiopress.cli.MainKt")
    applicationName = "audio-press"
}

tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("io/github/tjnewbry/audiopress/version.properties") {
        expand("version" to projectVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    // `./gradlew test -Pgolden.update=true` rewrites the golden files instead of comparing against them.
    systemProperty("golden.update", providers.gradleProperty("golden.update").getOrElse("false"))
    systemProperty(
        "golden.dir",
        layout.projectDirectory
            .dir("src/test/resources/golden")
            .asFile.absolutePath,
    )
}

spotless {
    kotlin {
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        ktlint(libs.versions.ktlint.get())
    }
}
