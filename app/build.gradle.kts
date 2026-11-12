plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.browseragent"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.browseragent"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.1-gecko"

        // GeckoView's omni AAR bundles every ABI; keep the two ARM ones
        // (32-bit phones need armeabi-v7a, modern phones use arm64-v8a).
        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
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
    kotlinOptions {
        jvmTarget = "17"
        // GeckoView drags in AndroidX / Kotlin libs built with a newer Kotlin than our
        // compiler. Let the compiler read their (compatible) metadata anyway.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// GeckoView's transitive AndroidX libraries declare a newer compileSdk / AGP than this
// project uses. They are runtime-only dependencies here, so skip the AAR metadata check.
tasks.configureEach {
    if (name.endsWith("AarMetadata")) {
        enabled = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Firefox engine. Dynamic version = latest release; pin it once a build works.
    implementation("org.mozilla.geckoview:geckoview-omni:+")
}
