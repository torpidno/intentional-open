// Top-level build file. Plugin versions live in gradle/libs.versions.toml.

// Sanitize java.library.path in the Gradle daemon process on Windows.
// Android Gradle Plugin's AndroidUnitTest task reads System.getProperty("java.library.path")
// at execution time and prepends it to the test worker launcher arguments without escaping quotes.
// If the Windows machine environment PATH contains quotes, java.library.path inherits them,
// breaking command-line parsing of the Gradle Test Executor worker process and failing with:
// "Error: Could not find or load main class Test"
val currentLibPath: String? = System.getProperty("java.library.path")
if (currentLibPath != null && currentLibPath.contains('"')) {
    System.setProperty("java.library.path", currentLibPath.replace("\"", ""))
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
