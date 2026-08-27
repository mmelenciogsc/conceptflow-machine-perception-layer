// SPDX-License-Identifier: MIT OR Apache-2.0
#include "yuv_rgb_converter.h"

#include <cstddef>
#include <cstdint>
#include <iostream>
#include <vector>

namespace {

std::size_t MinimumPlaneBytes(int width, int height, int row_stride, int pixel_stride) {
    return static_cast<std::size_t>(height - 1) * row_stride +
        static_cast<std::size_t>(width - 1) * pixel_stride + 1;
}

std::uint64_t Fnv1a64(const std::vector<std::uint8_t>& bytes) {
    std::uint64_t hash = 14695981039346656037ULL;
    for (const auto value : bytes) {
        hash ^= value;
        hash *= 1099511628211ULL;
    }
    return hash;
}

}  // namespace

int main() {
    constexpr int width = 648;
    constexpr int height = 648;
    constexpr int y_row_stride = 656;
    constexpr int chroma_width = (width + 1) / 2;
    constexpr int chroma_height = (height + 1) / 2;
    constexpr int chroma_row_stride = 656;
    constexpr int chroma_pixel_stride = 2;
    constexpr int y_offset = 7;
    constexpr int u_offset = 11;
    constexpr int v_offset = 13;
    std::vector<std::uint8_t> y(
        y_offset + MinimumPlaneBytes(width, height, y_row_stride, 1));
    std::vector<std::uint8_t> u(
        u_offset + MinimumPlaneBytes(
            chroma_width,
            chroma_height,
            chroma_row_stride,
            chroma_pixel_stride));
    std::vector<std::uint8_t> v(
        v_offset + MinimumPlaneBytes(
            chroma_width,
            chroma_height,
            chroma_row_stride,
            chroma_pixel_stride));
    for (int row = 0; row < height; ++row) {
        for (int column = 0; column < width; ++column) {
            y[y_offset + static_cast<std::size_t>(row) * y_row_stride + column] =
                static_cast<std::uint8_t>(16 + (column * 17 + row * 29) % 220);
        }
    }
    for (int row = 0; row < chroma_height; ++row) {
        for (int column = 0; column < chroma_width; ++column) {
            const auto u_index = u_offset + static_cast<std::size_t>(row) * chroma_row_stride +
                static_cast<std::size_t>(column) * chroma_pixel_stride;
            const auto v_index = v_offset + static_cast<std::size_t>(row) * chroma_row_stride +
                static_cast<std::size_t>(column) * chroma_pixel_stride;
            u[u_index] = static_cast<std::uint8_t>(16 + (column * 31 + row * 23) % 225);
            v[v_index] = static_cast<std::uint8_t>(16 + (column * 11 + row * 41) % 225);
        }
    }
    std::vector<std::uint8_t> output(640U * 640U * 3U);
    const bool converted = conceptflow::yuv::ConvertYuv420ToRgb8(
        {y.data(), y.size(), y_offset, y_row_stride, 1, width, height},
        {u.data(), u.size(), u_offset, chroma_row_stride, chroma_pixel_stride, chroma_width, chroma_height},
        {v.data(), v.size(), v_offset, chroma_row_stride, chroma_pixel_stride, chroma_width, chroma_height},
        {width, height, 640, 640, 640, 0, 0},
        output.data(),
        output.size());
    if (!converted) {
        std::cerr << "native conversion rejected a valid production-size frame\n";
        return 1;
    }
    const auto hash = Fnv1a64(output);
    constexpr std::uint64_t expected_kotlin_reference_hash = 7979620236997200776ULL;
    if (hash != expected_kotlin_reference_hash) {
        std::cerr << "golden mismatch: actual=" << hash << '\n';
        return 1;
    }
    if (conceptflow::yuv::ConvertYuv420ToRgb8(
            {y.data(), y.size(), y_offset, width - 1, 1, width, height},
            {u.data(), u.size(), u_offset, chroma_row_stride, chroma_pixel_stride, chroma_width, chroma_height},
            {v.data(), v.size(), v_offset, chroma_row_stride, chroma_pixel_stride, chroma_width, chroma_height},
            {width, height, 640, 640, 640, 0, 0},
            output.data(),
            output.size())) {
        std::cerr << "invalid luma row coverage was accepted\n";
        return 1;
    }
    if (conceptflow::yuv::ConvertYuv420ToRgb8(
            {y.data(), y.size(), y_offset, y_row_stride, 1, width, height},
            {u.data(), u.size(), u_offset, chroma_row_stride, chroma_pixel_stride, chroma_width, chroma_height},
            {v.data(), v.size(), v_offset, chroma_row_stride, chroma_pixel_stride, chroma_width, chroma_height},
            {width, height, 640, 640, 640, 0, 0},
            output.data(),
            output.size() - 1)) {
        std::cerr << "undersized RGB output was accepted\n";
        return 1;
    }
    return 0;
}
