// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Buffers.Binary;
using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public sealed record CaptureRegion(int X, int Y, int Width, int Height);

public static class ContentValidator
{
    private const int MaximumPngChunks = 4_096;
    private const int MaximumJpegHeaderBytes = 64 * 1024;
    private const int MaximumJpegMarkers = 512;

    private static readonly uint[] Crc32Table = CreateCrc32Table();

    private static ReadOnlySpan<byte> PngSignature => [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

    public static void Validate(CaptureSubmission submission, RelayOptions options)
    {
        ArgumentNullException.ThrowIfNull(submission);
        ArgumentNullException.ThrowIfNull(options);
        if (!submission.ConsentGranted)
        {
            throw new InvalidOperationException("Explicit capture consent is required.");
        }

        CaptureSourcePolicy.Validate(submission.Source);
        ProtocolValueValidator.ValidateIdentifier(submission.StreamId, "stream_id");
        ProtocolValueValidator.ValidateIdentifier(submission.RequestId, "request_id", required: false);

        if (submission.Width <= 0 || submission.Width > options.MaximumWidth ||
            submission.Height <= 0 || submission.Height > options.MaximumHeight)
        {
            throw new ArgumentOutOfRangeException(
                nameof(submission),
                $"Dimensions must be within {options.MaximumWidth} by {options.MaximumHeight} pixels.");
        }

        if (submission.Content.IsEmpty || submission.Content.Length > options.MaximumFrameBytes)
        {
            throw new ArgumentOutOfRangeException(
                nameof(submission),
                $"Content must contain 1 through {options.MaximumFrameBytes} bytes.");
        }

        if (!NegotiatedRelayPolicy.IsLocallySupportedEncoding(submission.Encoding))
        {
            throw new NotSupportedException($"Image encoding {submission.Encoding} is not supported by this relay.");
        }

        string expectedMediaType;
        switch (submission.Encoding)
        {
            case ImageEncoding.Png:
                expectedMediaType = "image/png";
                break;
            case ImageEncoding.Jpeg:
                expectedMediaType = "image/jpeg";
                break;
            default:
                throw new NotSupportedException($"Image encoding {submission.Encoding} is not supported by this relay.");
        }

        if (!string.Equals(submission.MediaType, expectedMediaType, StringComparison.Ordinal))
        {
            throw new ArgumentException(
                $"Media type must be exactly {expectedMediaType} for {submission.Encoding} content.",
                nameof(submission));
        }

        var encodedDimensions = submission.Encoding switch
        {
            ImageEncoding.Png => ParsePngDimensions(submission.Content.Span),
            ImageEncoding.Jpeg => ParseJpegDimensions(submission.Content.Span),
            _ => throw new NotSupportedException($"Image encoding {submission.Encoding} is not supported by this relay."),
        };

        if (encodedDimensions.Width != submission.Width || encodedDimensions.Height != submission.Height)
        {
            throw new ArgumentException("Encoded image dimensions do not match the descriptor.", nameof(submission));
        }

        if (submission.FrameId == 0)
        {
            throw new ArgumentException("A positive frame identifier is required.", nameof(submission));
        }
    }

    public static void ValidateRegion(CaptureRegion region, int maximumWidth, int maximumHeight)
    {
        ArgumentNullException.ThrowIfNull(region);
        if (region.X < 0 || region.Y < 0 || region.Width <= 0 || region.Height <= 0 ||
            region.Width > maximumWidth || region.Height > maximumHeight ||
            (long)region.X + region.Width > maximumWidth || (long)region.Y + region.Height > maximumHeight)
        {
            throw new ArgumentOutOfRangeException(nameof(region), "The selected region is outside the bounded capture surface.");
        }
    }

    private static (int Width, int Height) ParsePngDimensions(ReadOnlySpan<byte> data)
    {
        if (data.Length < 33 || !data.StartsWith(PngSignature) ||
            BinaryPrimitives.ReadUInt32BigEndian(data[8..12]) != 13 ||
            !data[12..16].SequenceEqual("IHDR"u8))
        {
            throw InvalidImage("PNG structure is invalid or truncated.");
        }

        var ihdr = data[16..29];
        if (CalculateCrc32(data[12..29]) != BinaryPrimitives.ReadUInt32BigEndian(data[29..33]))
        {
            throw InvalidImage("PNG structure is invalid or truncated.");
        }

        var width = BinaryPrimitives.ReadUInt32BigEndian(ihdr[..4]);
        var height = BinaryPrimitives.ReadUInt32BigEndian(ihdr[4..8]);
        var bitDepth = ihdr[8];
        var colorType = ihdr[9];
        if (width == 0 || height == 0 || !IsValidPngDepth(colorType, bitDepth) ||
            ihdr[10] != 0 || ihdr[11] != 0 || ihdr[12] > 1 ||
            width > int.MaxValue || height > int.MaxValue)
        {
            throw InvalidImage("PNG structure is invalid or truncated.");
        }

        var offset = 33;
        var chunks = 1;
        var sawImageData = false;
        while (offset < data.Length)
        {
            chunks++;
            if (chunks > MaximumPngChunks || data.Length - offset < 12)
            {
                throw InvalidImage("PNG structure is invalid or truncated.");
            }

            var chunkLength = BinaryPrimitives.ReadUInt32BigEndian(data[offset..(offset + 4)]);
            var chunkEnd = (long)offset + 12 + chunkLength;
            if (chunkEnd > data.Length)
            {
                throw InvalidImage("PNG structure is invalid or truncated.");
            }

            var chunkEndInt = checked((int)chunkEnd);
            var chunkType = data[(offset + 4)..(offset + 8)];
            var chunkDataEnd = checked(offset + 8 + (int)chunkLength);
            if (CalculateCrc32(data[(offset + 4)..chunkDataEnd]) !=
                BinaryPrimitives.ReadUInt32BigEndian(data[chunkDataEnd..chunkEndInt]))
            {
                throw InvalidImage("PNG structure is invalid or truncated.");
            }

            if (chunkType.SequenceEqual("IHDR"u8))
            {
                throw InvalidImage("PNG structure is invalid or truncated.");
            }

            if (chunkType.SequenceEqual("IDAT"u8))
            {
                sawImageData = true;
            }

            if (chunkType.SequenceEqual("IEND"u8))
            {
                if (chunkLength != 0 || !sawImageData || chunkEndInt != data.Length)
                {
                    throw InvalidImage("PNG structure is invalid or truncated.");
                }

                return (checked((int)width), checked((int)height));
            }

            offset = chunkEndInt;
        }

        throw InvalidImage("PNG structure is invalid or truncated.");
    }

    private static (int Width, int Height) ParseJpegDimensions(ReadOnlySpan<byte> data)
    {
        if (data.Length < 8 || data[0] != 0xff || data[1] != 0xd8 ||
            data[^2] != 0xff || data[^1] != 0xd9)
        {
            throw InvalidImage("JPEG structure is invalid or truncated.");
        }

        var offset = 2;
        var markerCount = 0;
        (int Width, int Height)? dimensions = null;
        var sawScan = false;
        var inEntropyData = false;
        while (offset < data.Length)
        {
            byte marker;
            if (inEntropyData)
            {
                var relativeMarkerOffset = data[offset..].IndexOf((byte)0xff);
                if (relativeMarkerOffset < 0 || offset + relativeMarkerOffset + 1 >= data.Length)
                {
                    throw InvalidImage("JPEG structure is invalid or truncated.");
                }

                offset += relativeMarkerOffset + 1;
                while (offset < data.Length && data[offset] == 0xff)
                {
                    offset++;
                }

                if (offset >= data.Length)
                {
                    throw InvalidImage("JPEG structure is invalid or truncated.");
                }

                marker = data[offset++];
                if (marker == 0x00 || marker is >= 0xd0 and <= 0xd7)
                {
                    continue;
                }

                inEntropyData = false;
            }
            else
            {
                if (dimensions is null && offset > MaximumJpegHeaderBytes)
                {
                    throw InvalidImage("JPEG header is invalid or exceeds scan limits.");
                }

                if (data[offset] != 0xff)
                {
                    throw InvalidImage("JPEG header is invalid or exceeds scan limits.");
                }

                while (offset < data.Length && data[offset] == 0xff)
                {
                    offset++;
                }

                if (offset >= data.Length)
                {
                    throw InvalidImage("JPEG structure is invalid or truncated.");
                }

                marker = data[offset++];
            }

            markerCount++;
            if (markerCount > MaximumJpegMarkers || marker is 0x00 or 0xd8)
            {
                throw InvalidImage("JPEG header is invalid or exceeds scan limits.");
            }

            if (marker == 0xd9)
            {
                if (dimensions is null || !sawScan || offset != data.Length)
                {
                    throw InvalidImage("JPEG structure is invalid or truncated.");
                }

                return dimensions.Value;
            }

            if (marker == 0x01 || marker is >= 0xd0 and <= 0xd7)
            {
                continue;
            }

            if (offset + 2 > data.Length)
            {
                throw InvalidImage("JPEG structure is invalid or truncated.");
            }

            var segmentLength = BinaryPrimitives.ReadUInt16BigEndian(data[offset..(offset + 2)]);
            if (segmentLength < 2 || offset + segmentLength > data.Length)
            {
                throw InvalidImage("JPEG structure is invalid or truncated.");
            }

            if (IsUnsupportedJpegStartOfFrame(marker))
            {
                throw InvalidImage("JPEG frame type is unsupported.");
            }

            if (marker is 0xc0 or 0xc1 or 0xc2)
            {
                if (dimensions is not null || segmentLength < 8)
                {
                    throw InvalidImage("JPEG structure is invalid or truncated.");
                }

                var precision = data[offset + 2];
                var height = BinaryPrimitives.ReadUInt16BigEndian(data[(offset + 3)..(offset + 5)]);
                var width = BinaryPrimitives.ReadUInt16BigEndian(data[(offset + 5)..(offset + 7)]);
                var components = data[offset + 7];
                if (width == 0 || height == 0 || precision is not (8 or 12) ||
                    components is not (1 or 3 or 4) || segmentLength != 8 + (3 * components))
                {
                    throw InvalidImage("JPEG structure is invalid or truncated.");
                }

                dimensions = (width, height);
            }

            if (marker == 0xda)
            {
                if (dimensions is null || segmentLength < 6)
                {
                    throw InvalidImage("JPEG does not contain a supported frame header.");
                }

                var scanComponents = data[offset + 2];
                if (scanComponents == 0 || segmentLength != 6 + (2 * scanComponents))
                {
                    throw InvalidImage("JPEG structure is invalid or truncated.");
                }

                sawScan = true;
                inEntropyData = true;
            }

            offset += segmentLength;
        }

        throw InvalidImage("JPEG structure is invalid or truncated.");
    }

    private static bool IsValidPngDepth(byte colorType, byte bitDepth) => colorType switch
    {
        0 => bitDepth is 1 or 2 or 4 or 8 or 16,
        2 => bitDepth is 8 or 16,
        3 => bitDepth is 1 or 2 or 4 or 8,
        4 or 6 => bitDepth is 8 or 16,
        _ => false,
    };

    private static bool IsUnsupportedJpegStartOfFrame(byte marker) => marker is
        0xc3 or 0xc5 or 0xc6 or 0xc7 or 0xc9 or 0xca or 0xcb or 0xcd or 0xce or 0xcf;

    private static uint CalculateCrc32(ReadOnlySpan<byte> data)
    {
        var crc = uint.MaxValue;
        foreach (var value in data)
        {
            crc = (crc >> 8) ^ Crc32Table[(crc ^ value) & 0xff];
        }

        return ~crc;
    }

    private static uint[] CreateCrc32Table()
    {
        var table = new uint[256];
        for (uint index = 0; index < table.Length; index++)
        {
            var value = index;
            for (var bit = 0; bit < 8; bit++)
            {
                value = (value >> 1) ^ ((value & 1) == 0 ? 0 : 0xedb88320u);
            }

            table[index] = value;
        }

        return table;
    }

    private static ArgumentException InvalidImage(string message) =>
        new(message, "submission");
}
