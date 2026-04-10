plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shanks.minify"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shanks.minify"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 🔥 Only support ARM architectures to kill x86 bloat
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            // 🔥 Strips debug symbols from native libs to save several MBs
            ndk {
                debugSymbolLevel = "none"
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Use debug signing for easy manual testing on your phone
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // 🔥 Force compression of native libraries (Saves ~10-20MB)
            useLegacyPackaging = true

            // Explicitly exclude all x86/x64 files
            excludes.add("lib/x86/**")
            excludes.add("lib/x86_64/**")

            // Prioritize your custom tiny engines
            pickFirsts.add("**/libavcodec.so")
            pickFirsts.add("**/libavformat.so")
            pickFirsts.add("**/libavutil.so")
            pickFirsts.add("**/libavfilter.so")
            pickFirsts.add("**/libswscale.so")
            pickFirsts.add("**/libswresample.so")
            pickFirsts.add("**/libavdevice.so")
            pickFirsts.add("**/libffmpegkit.so")
            pickFirsts.add("**/libffmpegkit_abidetect.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // FFmpeg Java Bridge
    implementation("com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
}