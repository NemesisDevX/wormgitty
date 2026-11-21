import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.wormgitty.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wormgitty.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // AndroidX base APIs used across the app (components, context extensions).
    implementation("androidx.core:core-ktx:1.15.0")
    // Lifecycle-aware coroutine scopes for collecting UI state safely.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Compose-hosting Activity glue.
    implementation("androidx.activity:activity-compose:1.9.3")
    // Aligns all Compose artifact versions from a single BOM.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    // Declarative UI toolkit used for the entire wizard UI.
    implementation("androidx.compose.ui:ui")
    // Material Design 3 components (buttons, text fields, checkboxes).
    implementation("androidx.compose.material3:material3")
    // Contract-grade JSON for wire payloads (nested objects, Unicode) replacing hand rolled escaping.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Unit-test assertions.
    testImplementation("junit:junit:4.13.2")
    // JVM-side Android framework emulation so receiver/service tests run locally.
    testImplementation("org.robolectric:robolectric:4.14.1")
    // AndroidX test primitives (ApplicationProvider) inside Robolectric tests.
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    testImplementation("androidx.test:core-ktx:1.6.1")

    // Standard instrumentation test runner plumbing.
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    // Espresso visibility/waiting helpers used by UI-flow assertions.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    // Compose assertion DSL inside instrumented tests.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Material icon-free debug inspector and test manifest for compose tests.
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
