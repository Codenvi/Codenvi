plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

val versionNameFromFile = rootProject.file("version.txt").readText().trim()
val versionCodeFromCi = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Release signing comes from environment variables only (docs/design.md §10.1). Without them (local builds,
// forks) the release build is signed with the debug key so it still installs.
// Every published APK must be signed with the SAME key, or Android refuses to update the installed app
// (it would have to be uninstalled, which deletes its pairing). CI sets REQUIRE_RELEASE_KEY=true so a build
// without the key fails instead of producing a debug-signed APK.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
// KEYSTORE_PATH is relative to the android/ folder (CI writes android/release.jks).
val keystoreFile = keystorePath?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
val hasReleaseKey = keystoreFile?.exists() == true
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
// Defaults match the keytool command in docs/building.md: alias "codenvi", key password = store password.
val signingKeyAlias: String = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "codenvi"
val signingKeyPassword: String? = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: keystorePassword
if (System.getenv("REQUIRE_RELEASE_KEY") == "true" && !hasReleaseKey) {
    throw GradleException("REQUIRE_RELEASE_KEY is set but no release keystore was found at KEYSTORE_PATH.")
}

android {
    namespace = "app.codenvi"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.codenvi"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeFromCi
        versionName = versionNameFromFile
        vectorDrawables { useSupportLibrary = true }
        // Offline speech-to-text (whisper.cpp) is built for 64-bit ARM phones only; other devices fall back to the system recognizer.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    ndkVersion = "26.3.11579264"

    // The pronunciation dictionary in assets/ is also read by the unit tests of the phonemizer.
    sourceSets { getByName("test").resources.srcDir("src/main/assets") }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get() }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin { jvmToolchain(17) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.onnxruntime.android) // runs the offline Kokoro voice (MIT)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging) // only enabled in debug builds (docs/design.md §10.2)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.code.scanner) // QR scan for pairing: Google-provided scanner screen, no CAMERA permission

    testImplementation(libs.junit)
}
