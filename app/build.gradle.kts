import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

        // Auto-populated with the actual date this APK was built, so the "About"
        // screen can always show an accurate "last updated" date without
        // needing a manual edit before every release.
        val buildDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")

        // JNA (a Vosk dependency) bundles native .so libraries for every Android
        // ABI, including x86/x86_64 which real phones never use (only emulators
        // do) — restricting to the two real-device ABIs drops several MB of dead
        // weight that was never going to run on an actual phone anyway.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // AGP normally auto-generates ~/.android/debug.keystore on first use, but on
    // GitHub Actions each run gets a brand-new machine, so a NEW random debug key
    // would be generated every single build. That means every APK would be signed
    // differently, and Android refuses to install an "update" over an existing app
    // with a different signature — forcing an uninstall before every reinstall.
    // Using a fixed, checked-in debug keystore instead means every build (local or
    // CI) is signed identically, so new builds always install cleanly as updates.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // Real release signing uses a PRIVATE keystore that is NEVER committed to
        // the repo — it's decoded from a GitHub Actions secret at build time (see
        // the workflow file). This is what actually addresses Play Protect's
        // "debug certificate" red flag: debug.keystore above uses a widely-shared,
        // publicly-known password/alias used by countless other apps, which is
        // itself a signal Play Protect flags. A unique private key removes that
        // specific signal.
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Fall back to debug signing if the release secrets aren't present
            // (e.g. a local build outside CI) so the build never just fails.
            signingConfig = if (!System.getenv("RELEASE_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Deliberately NOT setting jniLibs.useLegacyPackaging = true here.
        // Leaving it at AGP's modern default (uncompressed native libs, packaged
        // so the OS can execute them directly from the APK via mmap) is what
        // prioritizes performance: no separate extraction step at install time,
        // faster native library loading, and no duplicate extracted copy sitting
        // in app-private storage. The tradeoff is a somewhat larger APK download,
        // which matches the stated priority (speed/performance over storage size).
    }

    // Distinct filenames per build type so debug and release APKs never collide
    // when both are attached to the same GitHub Release.
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "VoiceCal-${buildType.name}.apk"
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
