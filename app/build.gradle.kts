plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.teamproject1.dailyexpensetracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.teamproject1.dailyexpensetracker"
        minSdk = 26        // Android 8.0 — required for WorkManager + Biometric reliability
        targetSdk = 35
        versionCode = 55
        versionName = "2.20.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Fixes a real bug: without an explicit signingConfig, Gradle falls back
    // to its default debug-keystore behavior, which on a CI runner (a fresh
    // machine every time, with no persisted ~/.android/debug.keystore from
    // a prior run) auto-generates a NEW random keystore on every build. Each
    // APK ends up signed with a different certificate, and Android correctly
    // refuses to install an "update" over a different signature — hence
    // "unable to update, please reinstall" on every single new build.
    // This keystore file is committed to the repo specifically so every CI
    // build reuses the exact same signature. Debug-only keystores like this
    // are normal to commit — a release keystore never should be.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // WorkManager (recurring transactions)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Biometric auth
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // Encrypted storage for PIN hash (Android Keystore-backed)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // DataStore (last-used book, settings)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Charts (Analytics screen) — using plain Compose primitives for now
    // instead of an external library, to avoid another dependency-version
    // resolution risk; can add a real charting library in a later pass.

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Room schema export — previously never configured (the "Schema export
// directory was not provided" warning seen in every build log), which
// meant every past schema change had no exported JSON to write an
// accurate Migration against. This is what made past schema changes
// (Bank, FD tenure) require a reinstall rather than a safe in-place
// migration. From the NEXT schema change onward, this gives a real JSON
// baseline to write migrations against instead.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
