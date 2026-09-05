// SPDX-License-Identifier: MIT OR Apache-2.0
#include "yuv_rgb_converter.h"

#include <algorithm>
#include <cstdint>
#include <limits>
#include <vector>

namespace conceptflow::yuv {
namespace {

constexpr int kFixedShift = 16;
constexpr int kFixedOne = 1 << kFixedShift;
constexpr std::int64_t kFixedHalf = static_cast<std::int64_t>(kFixedOne) / 2;
constexpr std::int64_t kFixedMask = static_cast<std::int64_t>(kFixedOne) - 1;
constexpr std::int64_t kFixedRound = static_cast<std::int64_t>(1) << (kFixedShift * 2 - 1);
constexpr int kRgbChannels = 3;
constexpr int kMaximumOutputSize = 2048;

struct AxisPlan {
    std::vector<int> lower;
    std::vector<int> upper;
    std::vector<int> upper_weight;
};

std::int64_t SampleCoordinate(int output_index, int output_size, int source_size) {
    const auto centered =
        ((2LL * output_index + 1LL) * source_size * kFixedOne) / (2LL * output_size) -
        kFixedHalf;
    return std::clamp<std::int64_t>(centered, 0, (source_size - 1LL) * kFixedOne);
}

AxisPlan CreateAxisPlan(
    int output_size,
    int scaled_size,
    int crop_offset,
    int source_size) {
    AxisPlan plan{
        std::vector<int>(static_cast<std::size_t>(output_size)),
        std::vector<int>(static_cast<std::size_t>(output_size)),
        std::vector<int>(static_cast<std::size_t>(output_size)),
    };
    for (int output_index = 0; output_index < output_size; ++output_index) {
        const auto coordinate =
            SampleCoordinate(output_index + crop_offset, scaled_size, source_size);
        const auto first = static_cast<int>(coordinate >> kFixedShift);
        plan.lower[static_cast<std::size_t>(output_index)] = first;
        plan.upper[static_cast<std::size_t>(output_index)] = std::min(first + 1, source_size - 1);
        plan.upper_weight[static_cast<std::size_t>(output_index)] =
            static_cast<int>(coordinate & kFixedMask);
    }
    return plan;
}

bool PlaneIsValid(const PlaneView& plane) {
    if (plane.data == nullptr || plane.offset < 0 || plane.row_stride <= 0 ||
        plane.pixel_stride <= 0 || plane.width <= 0 || plane.height <= 0) {
        return false;
    }
    const auto row_span = static_cast<std::uint64_t>(plane.width - 1) * plane.pixel_stride + 1;
    if (row_span > static_cast<std::uint64_t>(plane.row_stride)) return false;
    const auto last_byte = static_cast<std::uint64_t>(plane.offset) +
        static_cast<std::uint64_t>(plane.height - 1) * plane.row_stride +
        static_cast<std::uint64_t>(plane.width - 1) * plane.pixel_stride;
    return last_byte < plane.capacity;
}

inline int Read(const PlaneView& plane, int x, int y) {
    const auto index = static_cast<std::size_t>(plane.offset) +
        static_cast<std::size_t>(y) * static_cast<std::size_t>(plane.row_stride) +
        static_cast<std::size_t>(x) * static_cast<std::size_t>(plane.pixel_stride);
    return plane.data[index];
}

class RowCache {
  public:
    explicit RowCache(int output_width)
        : first_values_(static_cast<std::size_t>(output_width)),
          second_values_(static_cast<std::size_t>(output_width)) {}

    void Prepare(
        const PlaneView& plane,
        const AxisPlan& x,
        int lower_source_row,
        int upper_source_row) {
        int* lower = RowFor(lower_source_row);
        if (lower == nullptr) {
            if (upper_source_row == first_source_row_) {
                Fill(plane, x, lower_source_row, second_values_.data());
                second_source_row_ = lower_source_row;
                lower = second_values_.data();
            } else {
                Fill(plane, x, lower_source_row, first_values_.data());
                first_source_row_ = lower_source_row;
                lower = first_values_.data();
            }
        }
        int* upper = lower;
        if (upper_source_row != lower_source_row) {
            upper = RowFor(upper_source_row);
            if (upper == nullptr) {
                if (lower == first_values_.data()) {
                    Fill(plane, x, upper_source_row, second_values_.data());
                    second_source_row_ = upper_source_row;
                    upper = second_values_.data();
                } else {
                    Fill(plane, x, upper_source_row, first_values_.data());
                    first_source_row_ = upper_source_row;
                    upper = first_values_.data();
                }
            }
        }
        lower_values_ = lower;
        upper_values_ = upper;
    }

    const int* lower_values() const { return lower_values_; }
    const int* upper_values() const { return upper_values_; }

  private:
    int* RowFor(int source_row) {
        if (source_row == first_source_row_) return first_values_.data();
        if (source_row == second_source_row_) return second_values_.data();
        return nullptr;
    }

    static void Fill(
        const PlaneView& plane,
        const AxisPlan& x,
        int source_row,
        int* destination) {
        const auto size = x.lower.size();
        for (std::size_t output_x = 0; output_x < size; ++output_x) {
            const int upper_weight = x.upper_weight[output_x];
            destination[output_x] =
                Read(plane, x.lower[output_x], source_row) * (kFixedOne - upper_weight) +
                Read(plane, x.upper[output_x], source_row) * upper_weight;
        }
    }

    int first_source_row_ = -1;
    int second_source_row_ = -1;
    std::vector<int> first_values_;
    std::vector<int> second_values_;
    int* lower_values_ = nullptr;
    int* upper_values_ = nullptr;
};

inline int InterpolateRows(int lower, int upper, int upper_weight) {
    return static_cast<int>(
        (static_cast<std::int64_t>(lower) * (kFixedOne - upper_weight) +
         static_cast<std::int64_t>(upper) * upper_weight + kFixedRound) >>
        (kFixedShift * 2));
}

inline std::uint32_t LimitedRangeBt601Rgb(int y, int u, int v) {
    const int luminance = std::max(y - 16, 0);
    const int blue_difference = u - 128;
    const int red_difference = v - 128;
    // Negative channels clamp to zero, so truncating division is equivalent to Kotlin's
    // arithmetic shift after the clamp while avoiding implementation-defined signed shifts.
    const int red = std::clamp((298 * luminance + 409 * red_difference + 128) / 256, 0, 255);
    const int green = std::clamp(
        (298 * luminance - 100 * blue_difference - 208 * red_difference + 128) / 256,
        0,
        255);
    const int blue = std::clamp((298 * luminance + 516 * blue_difference + 128) / 256, 0, 255);
    return static_cast<std::uint32_t>((red << 16) | (green << 8) | blue);
}

bool TransformIsValid(const SquareAspectFillTransform& transform) {
    if (transform.source_width <= 0 || transform.source_height <= 0 ||
        transform.output_size <= 0 || transform.output_size > kMaximumOutputSize ||
        transform.scaled_width < transform.output_size ||
        transform.scaled_height < transform.output_size || transform.crop_left < 0 ||
        transform.crop_top < 0) {
        return false;
    }
    return transform.crop_left <= transform.scaled_width - transform.output_size &&
        transform.crop_top <= transform.scaled_height - transform.output_size;
}

void ResamplePlane(
    const PlaneView& plane,
    const AxisPlan& x,
    const AxisPlan& y,
    std::uint8_t* output) {
    RowCache rows(static_cast<int>(x.lower.size()));
    std::size_t offset = 0;
    for (std::size_t output_y = 0; output_y < y.lower.size(); ++output_y) {
        rows.Prepare(plane, x, y.lower[output_y], y.upper[output_y]);
        const int* lower = rows.lower_values();
        const int* upper = rows.upper_values();
        for (std::size_t output_x = 0; output_x < x.lower.size(); ++output_x) {
            output[offset++] = static_cast<std::uint8_t>(
                InterpolateRows(lower[output_x], upper[output_x], y.upper_weight[output_y]));
        }
    }
}

}  // namespace

bool ConvertYuv420ToRgb8(
    const PlaneView& y,
    const PlaneView& u,
    const PlaneView& v,
    const SquareAspectFillTransform& transform,
    std::uint8_t* output,
    std::size_t output_capacity) {
    if (!TransformIsValid(transform) || !PlaneIsValid(y) || !PlaneIsValid(u) ||
        !PlaneIsValid(v) || output == nullptr || y.width != transform.source_width ||
        y.height != transform.source_height || u.width != (y.width + 1) / 2 ||
        v.width != u.width || u.height != (y.height + 1) / 2 || v.height != u.height) {
        return false;
    }
    const auto output_size = static_cast<std::size_t>(transform.output_size);
    if (output_size > std::numeric_limits<std::size_t>::max() / output_size / kRgbChannels ||
        output_capacity < output_size * output_size * kRgbChannels) {
        return false;
    }

    const AxisPlan luma_x = CreateAxisPlan(
        transform.output_size,
        transform.scaled_width,
        transform.crop_left,
        y.width);
    const AxisPlan luma_y = CreateAxisPlan(
        transform.output_size,
        transform.scaled_height,
        transform.crop_top,
        y.height);
    const AxisPlan chroma_x = CreateAxisPlan(
        transform.output_size,
        transform.scaled_width,
        transform.crop_left,
        u.width);
    const AxisPlan chroma_y = CreateAxisPlan(
        transform.output_size,
        transform.scaled_height,
        transform.crop_top,
        u.height);

    RowCache y_rows(transform.output_size);
    RowCache u_rows(transform.output_size);
    RowCache v_rows(transform.output_size);
    std::size_t offset = 0;
    for (int output_y = 0; output_y < transform.output_size; ++output_y) {
        const auto row = static_cast<std::size_t>(output_y);
        y_rows.Prepare(y, luma_x, luma_y.lower[row], luma_y.upper[row]);
        u_rows.Prepare(u, chroma_x, chroma_y.lower[row], chroma_y.upper[row]);
        v_rows.Prepare(v, chroma_x, chroma_y.lower[row], chroma_y.upper[row]);
        const int* lower_y = y_rows.lower_values();
        const int* upper_y = y_rows.upper_values();
        const int* lower_u = u_rows.lower_values();
        const int* upper_u = u_rows.upper_values();
        const int* lower_v = v_rows.lower_values();
        const int* upper_v = v_rows.upper_values();
        const int luma_weight = luma_y.upper_weight[row];
        const int chroma_weight = chroma_y.upper_weight[row];
        for (int output_x = 0; output_x < transform.output_size; ++output_x) {
            const auto column = static_cast<std::size_t>(output_x);
            const auto packed = LimitedRangeBt601Rgb(
                InterpolateRows(lower_y[column], upper_y[column], luma_weight),
                InterpolateRows(lower_u[column], upper_u[column], chroma_weight),
                InterpolateRows(lower_v[column], upper_v[column], chroma_weight));
            output[offset++] = static_cast<std::uint8_t>((packed >> 16) & 0xffU);
            output[offset++] = static_cast<std::uint8_t>((packed >> 8) & 0xffU);
            output[offset++] = static_cast<std::uint8_t>(packed & 0xffU);
        }
    }
    return true;
}

bool ConvertYuv420ToI420(
    const PlaneView& y,
    const PlaneView& u,
    const PlaneView& v,
    const SquareAspectFillTransform& transform,
    std::uint8_t* output,
    std::size_t output_capacity) {
    if (!TransformIsValid(transform) || !PlaneIsValid(y) || !PlaneIsValid(u) ||
        !PlaneIsValid(v) || output == nullptr || y.width != transform.source_width ||
        y.height != transform.source_height || u.width != (y.width + 1) / 2 ||
        v.width != u.width || u.height != (y.height + 1) / 2 || v.height != u.height ||
        transform.output_size % 2 != 0 || transform.scaled_width % 2 != 0 ||
        transform.scaled_height % 2 != 0 || transform.crop_left % 2 != 0 ||
        transform.crop_top % 2 != 0) {
        return false;
    }
    const auto output_size = static_cast<std::size_t>(transform.output_size);
    if (output_size > std::numeric_limits<std::size_t>::max() / output_size) return false;
    const auto luma_bytes = output_size * output_size;
    if (luma_bytes > std::numeric_limits<std::size_t>::max() - luma_bytes / 2U) return false;
    const auto expected_bytes = luma_bytes + luma_bytes / 2U;
    if (output_capacity < expected_bytes) return false;

    const AxisPlan luma_x = CreateAxisPlan(
        transform.output_size,
        transform.scaled_width,
        transform.crop_left,
        y.width);
    const AxisPlan luma_y = CreateAxisPlan(
        transform.output_size,
        transform.scaled_height,
        transform.crop_top,
        y.height);
    const int chroma_output_size = transform.output_size / 2;
    const AxisPlan chroma_x = CreateAxisPlan(
        chroma_output_size,
        transform.scaled_width / 2,
        transform.crop_left / 2,
        u.width);
    const AxisPlan chroma_y = CreateAxisPlan(
        chroma_output_size,
        transform.scaled_height / 2,
        transform.crop_top / 2,
        u.height);
    const auto chroma_bytes = luma_bytes / 4U;
    ResamplePlane(y, luma_x, luma_y, output);
    ResamplePlane(u, chroma_x, chroma_y, output + luma_bytes);
    ResamplePlane(v, chroma_x, chroma_y, output + luma_bytes + chroma_bytes);
    return true;
}

}  // namespace conceptflow::yuv
