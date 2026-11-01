// The engine: data model, cleanup rules, paragraph assembly, writers. It depends only on the Kotlin standard
// library, so it can be reused outside the command line (for example in an Android app).
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}
