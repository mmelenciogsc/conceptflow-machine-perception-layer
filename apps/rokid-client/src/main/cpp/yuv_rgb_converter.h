// SPDX-License-Identifier: MIT OR Apache-2.0
#pragma once

#include <cstddef>
#include <cstdint>

namespace conceptflow::yuv {

struct PlaneView {
    const std::uint8_t* data;
    std::size_t capacity;
    int offset;
    int row_stride;
    int pixel_stride;
    int width;
    int height;
};

struct SquareAspectFillTransform {
    int source_width;
    int source_height;
    int output_size;
    int scaled_width;
    int scaled_height;
    int crop_left;
    int crop_top;
};

// Integer-only implementation of the Kotlin reference sampler and limited-range BT.601 mapping.
// Input planes remain borrowed by the caller; output must hold output_size * output_size * 3 bytes.
bool ConvertYuv420ToRgb8(
    const PlaneView& y,
    const PlaneView& u,
    const PlaneView& v,
    const SquareAspectFillTransform& transform,
    std::uint8_t* output,
    std::size_t output_capacity);

}  // namespace conceptflow::yuv
