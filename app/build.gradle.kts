import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystoreFile = rootProject.file("x11manager.keystore")
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD").orEmpty()
val releaseKeyAlias = System.getenv("KEY_ALIAS").orEmpty()
val releaseKeyPassword = System.getenv("KEY_PASSWORD").orEmpty()
val releaseSigningAvailable = releaseKeystoreFile.exists() &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank()
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning")
    .orNull
    ?.equals("true", ignoreCase = true) == true

if (requireReleaseSigning && !releaseSigningAvailable) {
    throw GradleException(
        "Production release signing was required, but x11manager.keystore or signing credentials are missing"
    )
}

android {
    namespace = "com.saas.x11manager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.saas.x11manager"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningAvailable) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
            signingConfig = if (releaseSigningAvailable) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Embedded Termux:X11/Lorie engine for the isolated integrated-X11 spike.
    implementation(project(":lorie"))
    // The pinned Lorie module keeps Preference as an implementation dependency.
    // Expose the same already-packaged API to this module so ScreenManager can
    // write the embedded Prefs store directly without an exported receiver hop.
    implementation("androidx.preference:preference:1.2.1")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Material Design (XML themes support)
    implementation("com.google.android.material:material:1.11.0")

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Root execution - libsu
    implementation("com.github.topjohnwu.libsu:core:5.2.1")
    implementation("com.github.topjohnwu.libsu:service:5.2.1")
    implementation("com.github.topjohnwu.libsu:io:5.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Unit tests
    testImplementation("junit:junit:4.13.2")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
