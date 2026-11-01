// Reads text-based PDFs with Apache PDFBox and feeds them to the engine. PDFBox does not run on Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":core"))
    implementation(libs.pdfbox)

    // Generates small PDFs for tests here and in the cli module. Its API exposes PDFBox types.
    testFixturesApi(libs.pdfbox)
}
