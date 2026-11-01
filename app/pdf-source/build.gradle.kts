// The Android PDF reader: implements the engine's TextSource with PdfBox-Android. Owned by the engine owner.
plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.tjnewbry.audiopress.pdfsource"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Only applies to this module's test APK. The app module needs the same rule (see the note in the dependencies block).
    packaging {
        resources {
            pickFirsts += "META-INF/LICENSE.md"
        }
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
    // The engine (../engine, included in settings.gradle.kts). TextSource and SourcePage are part of its API.
    api("io.github.tjnewbry.audiopress:core:0.1.0")
    // pdfbox-android 2.0.27.0 (December 2022, no longer updated) pulls in BouncyCastle 1.72, which has known
    // vulnerabilities. Swap in the maintained jdk18on build of the same packages (used for encrypted PDFs only).
    // The three jdk18on jars each carry META-INF/LICENSE.md, so a module that packages them (the app) needs
    // android { packaging { resources { pickFirsts += "META-INF/LICENSE.md" } } }.
    implementation(libs.pdfbox.android) {
        exclude(group = "org.bouncycastle")
    }
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.util)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)

    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
