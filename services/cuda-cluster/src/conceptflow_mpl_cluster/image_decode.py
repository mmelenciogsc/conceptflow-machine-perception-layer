# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded compressed-image decoding at the final worker dispatch boundary."""

from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
import warnings

from PIL import Image, UnidentifiedImageError

from conceptflow.mpl.v1 import perception_pb2 as pb
from conceptflow_mpl_host.preprocessing import FrameValidationError


_FORMAT_BY_ENCODING = {
    pb.IMAGE_ENCODING_JPEG: "JPEG",
    pb.IMAGE_ENCODING_PNG: "PNG",
}
_BYTES_PER_PIXEL = {
    "1": 1,
    "L": 1,
    "P": 1,
    "I;16": 2,
    "I": 4,
    "F": 4,
    "LA": 2,
    "RGB": 3,
    "RGBA": 4,
    "RGBX": 4,
    "CMYK": 4,
    "YCbCr": 3,
}


@dataclass(frozen=True, slots=True)
class BoundedImageDecoder:
    """Fully decode compressed frames without retaining decoded pixels."""

    max_width: int = 4_096
    max_height: int = 4_096
    max_decoded_pixels: int = 4_096 * 4_096
    max_decoded_bytes: int = 4_096 * 4_096 * 4

    def __post_init__(self) -> None:
        if min(self.max_width, self.max_height, self.max_decoded_pixels, self.max_decoded_bytes) <= 0:
            raise ValueError("decode limits must be positive")

    def validate(self, frame: pb.FramePayload) -> None:
        expected_format = _FORMAT_BY_ENCODING.get(frame.image.encoding)
        if expected_format is None:
            return
        declared_size = (frame.image.width, frame.image.height)
        self._validate_resource_bounds(declared_size, 1)
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(BytesIO(frame.frame_data)) as probe:
                    self._validate_image(probe, expected_format, declared_size)
                    probe.verify()
                with Image.open(BytesIO(frame.frame_data)) as decoded:
                    self._validate_image(decoded, expected_format, declared_size)
                    decoded.load()
                    if decoded.size != declared_size:
                        raise FrameValidationError(
                            pb.ERROR_CODE_INVALID_ARGUMENT,
                            "decoded image dimensions do not match descriptor",
                        )
        except FrameValidationError:
            raise
        except (Image.DecompressionBombError, Image.DecompressionBombWarning):
            raise FrameValidationError(
                pb.ERROR_CODE_OVERSIZE,
                "decoded image exceeds configured resource limits",
            ) from None
        except (OSError, SyntaxError, UnidentifiedImageError, ValueError):
            raise FrameValidationError(
                pb.ERROR_CODE_INVALID_ARGUMENT,
                "compressed image cannot be fully decoded",
            ) from None

    def _validate_image(
        self,
        image: Image.Image,
        expected_format: str,
        declared_size: tuple[int, int],
    ) -> None:
        if image.format != expected_format:
            raise FrameValidationError(
                pb.ERROR_CODE_INVALID_ARGUMENT,
                "decoded image format does not match encoding",
            )
        if image.size != declared_size:
            raise FrameValidationError(
                pb.ERROR_CODE_INVALID_ARGUMENT,
                "decoded image dimensions do not match descriptor",
            )
        bytes_per_pixel = _BYTES_PER_PIXEL.get(image.mode)
        if bytes_per_pixel is None:
            raise FrameValidationError(
                pb.ERROR_CODE_INVALID_ARGUMENT,
                "decoded image mode is unsupported",
            )
        self._validate_resource_bounds(image.size, bytes_per_pixel)

    def _validate_resource_bounds(self, size: tuple[int, int], bytes_per_pixel: int) -> None:
        width, height = size
        pixels = width * height
        if (
            width <= 0
            or height <= 0
            or width > self.max_width
            or height > self.max_height
            or pixels > self.max_decoded_pixels
            or pixels * bytes_per_pixel > self.max_decoded_bytes
        ):
            raise FrameValidationError(
                pb.ERROR_CODE_OVERSIZE,
                "decoded image exceeds configured resource limits",
            )
