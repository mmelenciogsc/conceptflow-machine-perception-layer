# SPDX-License-Identifier: MIT OR Apache-2.0
-dontwarn javax.annotation.**
-dontwarn com.squareup.okhttp.CipherSuite
-dontwarn com.squareup.okhttp.ConnectionSpec
-dontwarn com.squareup.okhttp.TlsVersion
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-keep class io.grpc.** { *; }

# qnn_jni.cpp uses statically named JNI exports for this one exact Kotlin object.
-keep class org.conceptflow.mpl.host.vision.QnnNativeBridge { *; }

# Unity's AndroidJavaClass calls these stable static entry points by name.
-keep class org.conceptflow.mpl.host.realtime.AndroidPerceptionBridge { *; }

# GenieX exposes Kotlin wrappers over statically named JNI entry points.
-keep class com.geniex.sdk.** { *; }
