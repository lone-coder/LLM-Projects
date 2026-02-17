plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yourcompany.anxietymonitor.wear"
    compileSdk = 36 // UPDATED: Match main app

    defaultConfig {
        applicationId = "com.yourcompany.anxietymonitor.wear"
        minSdk = 30 // Keep 30 for Wear OS 3.0+ requirement
        targetSdk = 34 // Match main app
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11 // UPDATED: Match main app
        targetCompatibility = JavaVersion.VERSION_11 // UPDATED: Match main app
    }

    kotlinOptions {
        jvmTarget = "11" // UPDATED: Match main app
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

composeCompiler {
    enableStrongSkippingMode = true
}

dependencies {
    // UPDATED: Core Wear OS dependencies
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear:wear-ongoing:1.0.0")

    // UPDATED: Wear Compose - Latest stable versions
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")

    // UPDATED: Compose - Match with latest stable
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.5")
    implementation("androidx.activity:activity-compose:1.9.3") // Match main app

    // Compose tooling for debugging
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.5")

    // UPDATED: Lifecycle - Match main app versions
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // UPDATED: Coroutines - Match main app
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // UPDATED: Wearable Data Layer - Match main app
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // UPDATED: Samsung health Sensor SDK - Match main app version
    implementation(files("../app/libs/samsung-health-sensor-api-v1.3.0.aar"))

    // UPDATED: JSON - Match main app
    implementation("org.json:json:20240303")

    // UPDATED: Core Android - Match main app versions
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.percentlayout:percentlayout:1.0.0")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}