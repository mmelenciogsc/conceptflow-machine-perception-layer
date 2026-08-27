// SPDX-License-Identifier: MIT OR Apache-2.0
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val forbiddenPrivateVoiceAssets = layout.projectDirectory
    .dir("src/main/assets/private/rokid_brand_voice")
    .asFile
check(!forbiddenPrivateVoiceAssets.exists() || forbiddenPrivateVoiceAssets.walkTopDown().none { it.isFile }) {
    "Private Rokid voice files must be provisioned into app no-backup storage, not packaged by Gradle."
}

android {
    namespace = "org.conceptflow.mpl.rokid"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.conceptflow.mpl.rokidclient"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    lint {
        // Rokid Style is an arm64-only appliance target, not a ChromeOS-distributed APK.
        disable += "ChromeOsAbiSupport"
    }
}

dependencies {
    implementation(project(":packages:android-protocol"))
    implementation(project(":packages:android-live-transport"))
    implementation(libs.grpc.okhttp)
    testImplementation(libs.junit)
}
