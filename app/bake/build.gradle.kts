// Bake: turns the engine's utterances into audio files with Android's TextToSpeech. No UI. Owned by the engine owner for now.
plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.tjnewbry.audiopress.bake"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // The engine (../engine, included in settings.gradle.kts). Utterance and CancellationToken are part of this module's API.
    api("io.github.tjnewbry.audiopress:core:0.1.0")

    // Runs the Kokoro models. Exactly 1.28.0: later versions add the INTERNET permission and telemetry that is on by default.
    implementation(libs.onnxruntime.android)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)

    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
