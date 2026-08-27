// SPDX-License-Identifier: MIT OR Apache-2.0
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val qnnSdkRoot = providers.gradleProperty("qnnSdkRoot")
    .orElse(providers.environmentVariable("QNN_SDK_ROOT"))
    .orNull
val requireQnn = providers.gradleProperty("requireQnn")
    .map(String::toBooleanStrict)
    .orElse(false)

if (requireQnn.get() && qnnSdkRoot == null) {
    error("-PrequireQnn=true requires -PqnnSdkRoot=DIR or QNN_SDK_ROOT=DIR")
}

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
    packaging {
        // GenieX resolves its runtime plug-ins through applicationInfo.nativeLibraryDir.
        jniLibs.useLegacyPackaging = true
    }
    sourceSets.getByName("main").resources.srcDir(rootProject.file("config/machine-vision"))
}

dependencies {
    implementation(project(":packages:android-protocol"))
    implementation(project(":packages:android-live-transport"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.grpc.okhttp)
    implementation(libs.geniex.android)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
