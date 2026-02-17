// Project-level build.gradle.kts - Latest Stable Versions
// Top-level build file
plugins {
    id("com.android.application") version "8.9.1" apply false
    // STABLE: Kotlin 2.0.21 (Kotlin 2.1.0+ has known Hilt compatibility issues)
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // LATEST: Hilt 2.52 (released November 2024)
    id("com.google.dagger.hilt.android") version "2.52" apply false
    // MATCHING: KSP version that matches Kotlin 2.0.21
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    alias(libs.plugins.kotlin.compose) apply false
}
