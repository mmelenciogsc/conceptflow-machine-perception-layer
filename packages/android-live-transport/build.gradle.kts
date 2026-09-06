// SPDX-License-Identifier: MIT OR Apache-2.0
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.conceptflow.mpl.transport"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":packages:android-protocol"))
    implementation(libs.lz4.java)
    compileOnly(libs.zstd.jni)
    runtimeOnly(variantOf(libs.zstd.jni) { artifactType("aar") })
    testImplementation(libs.junit)
    testRuntimeOnly(libs.zstd.jni)
}
