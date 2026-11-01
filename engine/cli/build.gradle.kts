// The audio-press command-line program.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":pdfbox"))
    implementation(libs.clikt.core)

    testImplementation(testFixtures(project(":pdfbox")))
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
    // `./gradlew test -Pgolden.update=true` rewrites the golden files instead of comparing against them.
    systemProperty("golden.update", providers.gradleProperty("golden.update").getOrElse("false"))
    systemProperty(
        "golden.dir",
        layout.projectDirectory
            .dir("src/test/resources/golden")
            .asFile.absolutePath,
    )
}
