import java.util.Properties

val keystoreProperties = Properties()
val keystoreFile = rootProject.file("release.keystore")
if (keystoreFile.exists()) {
    keystoreProperties.load(keystoreFile.inputStream())
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.tan.ppgtoolapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.tan.ppgtoolapp"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.4"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = System.getenv("KEYSTORE_STORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword")
                ?: "12345678"
            keyAlias = System.getenv("KEYSTORE_KEY_ALIAS")
                ?: keystoreProperties.getProperty("keyAlias")
                ?: "ppgtool"
            keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD")
                ?: keystoreProperties.getProperty("keyPassword")
                ?: "12345678"
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
            signingConfig = signingConfigs.getByName("release")
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
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.preview)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Activity & Lifecycle
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.coroutines.android)

    // 7z extraction
    implementation(libs.commons.compress)
    implementation(libs.xz)

    // Core
    implementation(libs.core.ktx)
}
