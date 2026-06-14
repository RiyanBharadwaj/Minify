plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shanks.minify"
    compileSdk = 37

    androidResources {
        localeFilters += "en"
    }

    defaultConfig {
        applicationId = "com.shanks.minify"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "6.0"

        ndk {
            // This already tells Android to only include 64 bit architecture
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            ndk {
                debugSymbolLevel = "none"
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // Required for 16KB page alignment on Android 15
            useLegacyPackaging = false

            // Manual excludes removed as abiFilters above handles this automatically
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.compose.ui.geometry)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    implementation("androidx.media3:media3-common:1.10.1")

    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
}
