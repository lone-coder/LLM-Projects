// App-level build.gradle.kts - Latest Stable Versions
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("dagger.hilt.android.plugin")
    id("kotlin-parcelize")
}

android {
    namespace = "com.yourcompany.anxietymonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yourcompany.anxietymonitor"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
        // FIXED: Updated deprecated pickFirst syntax
        jniLibs {
            pickFirsts += "**/libtensorflowlite_jni.so"
            pickFirsts += "**/libtensorflowlite_c.so"
        }
    }

    dataBinding {
        enable = true
    }
}

dependencies {
    // UPDATED: Core Android dependencies
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // health Connect - Your requested version

    implementation("androidx.health.connect:connect-client:1.1.0-rc02")

    // Samsung
    implementation(files("libs/samsung-health-sensor-api-v1.3.0.aar"))
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // TensorFlow Lite - STABLE VERSIONS
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-task-text:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // Room Database - STABLE VERSION
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Lifecycle - UPDATED
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Coroutines - UPDATED
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // LATEST: Hilt Dependency Injection 2.52
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")

    // UPDATED: Hilt Work Manager Support
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")



    // Security - STABLE VERSIONS
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")

    // Work Manager - UPDATED
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // JSON - UPDATED
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.json:json:20240303")


    // HTTP Client - UPDATED
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")

    // Google Play Services Tasks - UPDATED
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Testing - UPDATED
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // UPDATED: Activity and Fragment to latest versions
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")


}