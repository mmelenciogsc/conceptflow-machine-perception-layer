// SPDX-License-Identifier: MIT OR Apache-2.0
#include "yuv_rgb_converter.h"

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <new>

namespace {

conceptflow::yuv::PlaneView MakePlane(
    JNIEnv* env,
    jobject buffer,
    jint offset,
    jint row_stride,
    jint pixel_stride,
    jint width,
    jint height) {
    return conceptflow::yuv::PlaneView{
        static_cast<const std::uint8_t*>(env->GetDirectBufferAddress(buffer)),
        static_cast<std::size_t>(env->GetDirectBufferCapacity(buffer)),
        offset,
        row_stride,
        pixel_stride,
        width,
        height,
    };
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_org_conceptflow_mpl_rokid_hardware_NativeYuv420RgbConverter_convert(
    JNIEnv* env,
    jobject,
    jobject y_buffer,
    jint y_offset,
    jint y_row_stride,
    jint y_pixel_stride,
    jobject u_buffer,
    jint u_offset,
    jint u_row_stride,
    jint u_pixel_stride,
    jobject v_buffer,
    jint v_offset,
    jint v_row_stride,
    jint v_pixel_stride,
    jint source_width,
    jint source_height,
    jint output_size,
    jint scaled_width,
    jint scaled_height,
    jint crop_left,
    jint crop_top,
    jbyteArray output) {
    if (y_buffer == nullptr || u_buffer == nullptr || v_buffer == nullptr || output == nullptr) {
        return JNI_FALSE;
    }
    const int chroma_width = (source_width + 1) / 2;
    const int chroma_height = (source_height + 1) / 2;
    const auto y = MakePlane(
        env,
        y_buffer,
        y_offset,
        y_row_stride,
        y_pixel_stride,
        source_width,
        source_height);
    const auto u = MakePlane(
        env,
        u_buffer,
        u_offset,
        u_row_stride,
        u_pixel_stride,
        chroma_width,
        chroma_height);
    const auto v = MakePlane(
        env,
        v_buffer,
        v_offset,
        v_row_stride,
        v_pixel_stride,
        chroma_width,
        chroma_height);
    const auto transform = conceptflow::yuv::SquareAspectFillTransform{
        source_width,
        source_height,
        output_size,
        scaled_width,
        scaled_height,
        crop_left,
        crop_top,
    };
    const auto output_capacity = static_cast<std::size_t>(env->GetArrayLength(output));
    auto* output_bytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(output, nullptr));
    if (output_bytes == nullptr) return JNI_FALSE;
    bool converted = false;
    try {
        converted = conceptflow::yuv::ConvertYuv420ToRgb8(
            y,
            u,
            v,
            transform,
            reinterpret_cast<std::uint8_t*>(output_bytes),
            output_capacity);
    } catch (const std::bad_alloc&) {
        converted = false;
    }
    env->ReleasePrimitiveArrayCritical(output, output_bytes, converted ? 0 : JNI_ABORT);
    return converted ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_conceptflow_mpl_rokid_hardware_NativeYuv420I420Converter_convert(
    JNIEnv* env,
    jobject,
    jobject y_buffer,
    jint y_offset,
    jint y_row_stride,
    jint y_pixel_stride,
    jobject u_buffer,
    jint u_offset,
    jint u_row_stride,
    jint u_pixel_stride,
    jobject v_buffer,
    jint v_offset,
    jint v_row_stride,
    jint v_pixel_stride,
    jint source_width,
    jint source_height,
    jint output_size,
    jint scaled_width,
    jint scaled_height,
    jint crop_left,
    jint crop_top,
    jbyteArray output) {
    if (y_buffer == nullptr || u_buffer == nullptr || v_buffer == nullptr || output == nullptr) {
        return JNI_FALSE;
    }
    const int chroma_width = (source_width + 1) / 2;
    const int chroma_height = (source_height + 1) / 2;
    const auto y = MakePlane(
        env, y_buffer, y_offset, y_row_stride, y_pixel_stride, source_width, source_height);
    const auto u = MakePlane(
        env, u_buffer, u_offset, u_row_stride, u_pixel_stride, chroma_width, chroma_height);
    const auto v = MakePlane(
        env, v_buffer, v_offset, v_row_stride, v_pixel_stride, chroma_width, chroma_height);
    const auto transform = conceptflow::yuv::SquareAspectFillTransform{
        source_width,
        source_height,
        output_size,
        scaled_width,
        scaled_height,
        crop_left,
        crop_top,
    };
    const auto output_capacity = static_cast<std::size_t>(env->GetArrayLength(output));
    auto* output_bytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(output, nullptr));
    if (output_bytes == nullptr) return JNI_FALSE;
    bool converted = false;
    try {
        converted = conceptflow::yuv::ConvertYuv420ToI420(
            y,
            u,
            v,
            transform,
            reinterpret_cast<std::uint8_t*>(output_bytes),
            output_capacity);
    } catch (const std::bad_alloc&) {
        converted = false;
    }
    env->ReleasePrimitiveArrayCritical(output, output_bytes, converted ? 0 : JNI_ABORT);
    return converted ? JNI_TRUE : JNI_FALSE;
}
