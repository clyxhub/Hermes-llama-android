plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Stage (b): Vulkan GPU backend.
//
// llama.cpp's shader generator shells out to a *host* `glslc`. The NDK ships
// one under shader-tools/ that matches the installed NDK, so prefer it when
// present; the CI workflow also puts glslc on PATH as a fallback.
// ---------------------------------------------------------------------------
val ndkHostTag: String = when {
    System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
    System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
    else -> "linux-x86_64"
}

val ndkGlslc: java.io.File? = listOfNotNull(
    System.getenv("ANDROID_NDK_HOME"),
    System.getenv("ANDROID_NDK_ROOT"),
    System.getenv("ANDROID_HOME")?.let { "$it/ndk/26.1.10909125" },
    System.getenv("ANDROID_SDK_ROOT")?.let { "$it/ndk/26.1.10909125" }
).map { java.io.File(it, "shader-tools/$ndkHostTag/glslc") }.firstOrNull { it.isFile }

android {
    namespace = "com.hermes.llama"
    compileSdk = 34
    // Pinned to match the NDK installed in CI (.github/workflows/build.yml).
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.hermes.llama"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Stage (a): arm64 only. x86_64 added later for emulator testing.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Stage (b): the Vulkan GPU backend is enabled in
                // app/src/main/cpp/CMakeLists.txt (GGML_VULKAN=ON) together with
                // the vendored Vulkan/SPIR-V dev-kit under src/main/cpp/vulkan.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DGGML_OPENMP=OFF"
                )
                // Pin the host glslc used by llama.cpp's shader generator.
                ndkGlslc?.let { arguments += "-DVulkan_GLSLC_EXECUTABLE=${it.absolutePath}" }
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key for now so `assembleRelease` works in CI
            // without secrets. Swap to a real keystore before any distribution.
            signingConfig = signingConfigs.getByName("debug")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // Required for androidx.lifecycle.viewmodel.compose.viewModel() used by
    // ChatScreen(); without it compileReleaseKotlin fails with
    // "Unresolved reference: compose / viewModel".
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
