// SPDX-License-Identifier: MIT OR Apache-2.0
import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "org.conceptflow.mpl.protocol"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.named("main") {
        proto {
            srcDir("../shared-protocol/proto")
        }
    }
    sourceSets.named("test") {
        resources.srcDir("../../tests/fixtures")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.asProvider().get()}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                id("java") {
                    option("lite")
                }
            }
            plugins {
                id("grpc") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api(libs.protobuf.javalite)
    api(libs.grpc.protobuf.lite)
    api(libs.grpc.stub)
    compileOnly(libs.javax.annotation)
    testImplementation(libs.junit)
}
