plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The APK carries no API keys: everyone pastes their own free AT key into
// Settings. Only the CI screenshot run sets EMBED_KEYS=true so its emulator
// has live data, and that APK never leaves the runner.
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val embedKeys = System.getenv("EMBED_KEYS") == "true"
val atKey = if (embedKeys) System.getenv("AT_API_KEY") ?: "" else ""
val linzKey = if (embedKeys) System.getenv("LINZ_API_KEY") ?: "" else ""
val keystore = file("release.p12")

android {
    namespace = "nz.aryan.akllive"
    compileSdk = 35

    defaultConfig {
        applicationId = "nz.aryan.akllive"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        buildConfigField("String", "AT_API_KEY", "\"$atKey\"")
        buildConfigField("String", "LINZ_API_KEY", "\"$linzKey\"")
        // the map's native code: 64-bit phones (and the x86_64 emulator in CI)
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        create("release") {
            if (keystore.exists()) {
                storeFile = keystore
                storeType = "pkcs12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "release"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 keeps the APK small (the full Material icon set is big unshrunk)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystore.exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Material 3's app bars, sheets, pickers and pull-to-refresh are still marked experimental
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // home screen widget, and the background refresh behind it
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // open-source vector/raster maps (the satellite and street views)
    implementation("org.maplibre.gl:android-sdk:12.3.1")
}
