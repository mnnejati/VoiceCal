import java.util.UUID

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ir.appointment.voice"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.appointment.voice"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Offline, on-device speech recognition (no Google servers, no network needed).
    // A single exclusive AudioRecord session feeds both the WAV file and the
    // recognizer, eliminating the mic-contention issue that occurs when two
    // separate audio clients (e.g. MediaRecorder + Android's SpeechRecognizer)
    // try to use the microphone at the same time.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

// Vosk requires a "uuid" file inside the model folder; recent official model
// downloads from alphacephei.com don't include one, which otherwise causes a
// "Failed to unpack the model" error at runtime. This generates it
// automatically so no manual step is needed beyond copying the raw model
// folder into assets. Safe no-op if the offline model folder isn't present
// (offline mode is entirely optional).
tasks.register("ensureVoskModelUuid") {
    doLast {
        val modelDir = file("src/main/assets/model-fa-fa")
        if (modelDir.exists() && modelDir.isDirectory) {
            val uuidFile = file("$modelDir/uuid")
            if (!uuidFile.exists()) {                
                uuidFile.writeText(UUID.randomUUID().toString())
                println("Generated missing Vosk model uuid file at $uuidFile")
            }
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("ensureVoskModelUuid")
}
