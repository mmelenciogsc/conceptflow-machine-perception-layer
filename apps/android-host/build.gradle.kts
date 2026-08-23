// SPDX-License-Identifier: MIT OR Apache-2.0
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val qnnSdkRoot = providers.gradleProperty("qnnSdkRoot")
    .orElse(providers.environmentVariable("QNN_SDK_ROOT"))
    .orNull

android {
    namespace = "org.conceptflow.mpl.host"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.conceptflow.mpl.androidhost"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (qnnSdkRoot != null) {
            ndk { abiFilters += "arm64-v8a" }
            externalNativeBuild {
                cmake {
                    arguments += "-DQNN_SDK_ROOT=$qnnSdkRoot"
                }
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
    if (qnnSdkRoot != null) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    implementation(project(":packages:android-protocol"))
    implementation(project(":packages:android-live-transport"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.grpc.okhttp)
    testImplementation(libs.junit)
}
