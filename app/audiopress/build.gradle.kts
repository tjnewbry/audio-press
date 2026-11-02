plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.tjnewbry.audiopress.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.tjnewbry.audiopress.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // onnxruntime ships native libraries for four ABIs; keep the phone (arm64) and the emulator (x86_64).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            // BouncyCastle (used by the PDF reader) ships the same license file in three jars.
            pickFirsts += "META-INF/LICENSE.md"
        }
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // The engine (../engine, included in settings.gradle.kts). Use it only through engine/docs/api.md.
    implementation("io.github.tjnewbry.audiopress:core:0.1.0")

    // The Android PDF reader, which implements the engine's TextSource (owned by the engine owner).
    implementation(project(":pdf-source"))
    // Turns utterances into audio files (owned by the engine owner for now).
    implementation(project(":bake"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

// The licences screen shows one text file, put together at build time from the app's own notices and the notices and
// licence texts kept in the bake module, so the screen cannot fall behind them.
abstract class GenerateNotices : DefaultTask() {
    @get:InputFile
    abstract val appNotices: RegularFileProperty

    @get:InputFile
    abstract val bakeNotices: RegularFileProperty

    @get:InputDirectory
    abstract val licenseTexts: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val text =
            buildString {
                append(
                    appNotices
                        .get()
                        .asFile
                        .readText()
                        .trim(),
                ).append("\n\n")
                append(
                    bakeNotices
                        .get()
                        .asFile
                        .readText()
                        .trim(),
                ).append("\n\n")
                licenseTexts.get().asFile.listFiles { f -> f.extension == "txt" }.orEmpty().sortedBy { it.name }.forEach {
                    append("----\n\n")
                        .append(it.name.removeSuffix(".txt"))
                        .append("\n\n")
                        .append(it.readText().trim())
                        .append("\n\n")
                }
            }
        val target = outputDir.get().asFile
        target.mkdirs()
        target.resolve("licences.txt").writeText(text)
    }
}

val generateNotices =
    tasks.register<GenerateNotices>("generateNotices") {
        appNotices.set(layout.projectDirectory.file("NOTICES.md"))
        bakeNotices.set(layout.projectDirectory.file("../bake/THIRD-PARTY-NOTICES.md"))
        licenseTexts.set(layout.projectDirectory.dir("../bake/licenses"))
        outputDir.set(layout.buildDirectory.dir("generated/notices"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateNotices, GenerateNotices::outputDir)
    }
}
