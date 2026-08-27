# SPDX-License-Identifier: MIT OR Apache-2.0
-dontwarn javax.annotation.**

# JNI entry point is registered by its stable class/method name.
-keep class org.conceptflow.mpl.rokid.hardware.NativeYuv420RgbConverter { *; }
