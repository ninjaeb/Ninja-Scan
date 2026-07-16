import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing is optional: absent locally, it's supplied by CI via
// app/keystore.properties (git-ignored, written from GitHub secrets — see
// docs/RELEASE.md). Without it, `bundleRelease` still builds, just unsigned,
// exactly as it does today.
val keystorePropsFile = rootProject.file("app/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}
fun releaseProp(name: String) = keystoreProps.getProperty(name) ?: System.getenv(name)
val hasReleaseSigning = releaseProp("RELEASE_STORE_FILE") != null &&
    releaseProp("RELEASE_STORE_PASSWORD") != null &&
    releaseProp("RELEASE_KEY_ALIAS") != null &&
    releaseProp("RELEASE_KEY_PASSWORD") != null

android {
    namespace = "com.ninja.scan"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ninja.scan"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Fixed debug key committed to the repo so CI builds keep a stable
        // signature: updates install over each other, and the SHA-1
        // registered for the Google Drive OAuth client stays valid.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseProp("RELEASE_STORE_FILE")!!)
                storePassword = releaseProp("RELEASE_STORE_PASSWORD")
                keyAlias = releaseProp("RELEASE_KEY_ALIAS")
                keyPassword = releaseProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                // Produces app/build/outputs/native-debug-symbols/release/
                // native-debug-symbols.zip during bundleRelease, for Play
                // Console's (optional) native crash symbolication.
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // ML Kit document scanner: capture, edge detection, auto-crop, cleanup filters
    implementation(libs.mlkit.document.scanner)

    // ML Kit text recognition (on-device, via Play services): searchable scans
    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.play.services)

    // Google Drive backup: OAuth authorization + background uploads
    implementation(libs.play.services.auth)
    implementation(libs.androidx.work.runtime.ktx)

    // App-open biometric lock
    implementation(libs.androidx.biometric)

    // Local library of scans
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // org.json is Android-provided at runtime but stubbed (throws) in local
    // JVM unit tests; pull in the real implementation for DriveManifestTest.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
