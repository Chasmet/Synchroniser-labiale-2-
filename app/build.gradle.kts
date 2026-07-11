import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val encodedTestKeystore = rootProject.file("keystore/lipsync-test.jks.b64")
val decodedTestKeystore = layout.buildDirectory.file("generated/lipsync-test.jks").get().asFile
if (!decodedTestKeystore.exists()) {
    decodedTestKeystore.parentFile.mkdirs()
    decodedTestKeystore.writeBytes(
        Base64.getDecoder().decode(encodedTestKeystore.readText().trim())
    )
}

android {
    namespace = "com.chasmet.lipsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chasmet.lipsync"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.8.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        getByName("debug") {
            storeFile = decodedTestKeystore
            storePassword = "lipsync-test-2026"
            keyAlias = "lipsync-test"
            keyPassword = "lipsync-test-2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    androidResources {
        // Les modèles sont déjà des fichiers binaires compressés ou mappés.
        noCompress += listOf("onnx", "zip")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("com.github.wendykierp:JTransforms:3.1")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")

    testImplementation("junit:junit:4.13.2")
}
