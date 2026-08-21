// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.DesktopRelay.Core;
using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core.Tests;

public sealed class QueueAndContentTests
{
    [Fact]
    public void RejectNewestPolicy_AppliesBackpressureWithoutMutation()
    {
        var queue = new BoundedRelayQueue<string>(2, QueueOverflowPolicy.RejectNewest);
        queue.Enqueue("one");
        queue.Enqueue("two");

        var third = queue.Enqueue("three");

        Assert.False(third.Accepted);
        Assert.Equal(2, queue.Count);
        Assert.True(queue.TryDequeue(out var first));
        Assert.Equal("one", first);
    }

    [Fact]
    public void DropOldestPolicy_IsFifoForRetainedItems()
    {
        var queue = new BoundedRelayQueue<string>(2, QueueOverflowPolicy.DropOldest);
        queue.Enqueue("one");
        queue.Enqueue("two");

        var third = queue.Enqueue("three");

        Assert.True(third.Accepted);
        Assert.Equal("one", third.DroppedItem);
        Assert.True(queue.TryDequeue(out var first));
        Assert.True(queue.TryDequeue(out var second));
        Assert.Equal("two", first);
        Assert.Equal("three", second);
    }

    [Fact]
    public void ContentValidator_RejectsOversizePayload()
    {
        var capture = TestFrames.Capture();
        var options = new RelayOptions
        {
            MaximumFrameBytes = capture.Content.Length - 1,
            MaximumMessageBytes = capture.Content.Length,
        };

        Assert.Throws<ArgumentOutOfRangeException>(() =>
            ContentValidator.Validate(capture, options));
    }

    [Theory]
    [InlineData(ImageEncoding.Png)]
    [InlineData(ImageEncoding.Jpeg)]
    public void ContentValidator_AcceptsMatchingImageSignatureAndMediaType(ImageEncoding encoding)
    {
        ContentValidator.Validate(TestFrames.Capture(encoding: encoding), new RelayOptions());
    }

    [Theory]
    [InlineData(ImageEncoding.Png, "image/jpeg")]
    [InlineData(ImageEncoding.Jpeg, "image/png")]
    [InlineData(ImageEncoding.Jpeg, "image/jpg")]
    [InlineData(ImageEncoding.Png, "image/png; charset=binary")]
    [InlineData(ImageEncoding.Png, "IMAGE/PNG")]
    [InlineData(ImageEncoding.Jpeg, "IMAGE/JPEG")]
    public void ContentValidator_RejectsMismatchedOrInexactMediaType(ImageEncoding encoding, string mediaType)
    {
        var submission = TestFrames.Capture(encoding: encoding) with { MediaType = mediaType };

        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(submission, new RelayOptions()));
    }

    [Theory]
    [InlineData(ImageEncoding.Png, 8)]
    [InlineData(ImageEncoding.Png, 7)]
    [InlineData(ImageEncoding.Jpeg, 3)]
    [InlineData(ImageEncoding.Jpeg, 2)]
    public void ContentValidator_RejectsBadOrTruncatedImageSignature(ImageEncoding encoding, int byteCount)
    {
        var badSignature = TestFrames.Capture(encoding: encoding) with { Content = new byte[byteCount] };

        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(badSignature, new RelayOptions()));
    }

    [Fact]
    public void ContentValidator_RejectsPngBytesDeclaredAsJpeg()
    {
        var disguisedPng = TestFrames.Capture() with
        {
            Encoding = ImageEncoding.Jpeg,
            MediaType = "image/jpeg",
        };

        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(disguisedPng, new RelayOptions()));
    }

    [Theory]
    [InlineData(1)]
    [InlineData(8)]
    [InlineData(24)]
    [InlineData(32)]
    [InlineData(67)]
    public void ContentValidator_RejectsTruncatedPngStructure(int byteCount)
    {
        var capture = TestFrames.Capture();
        var truncated = capture with { Content = capture.Content[..byteCount] };

        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(truncated, new RelayOptions()));
    }

    [Fact]
    public void ContentValidator_RejectsPngCrcTrailingDataAndDimensionMismatch()
    {
        var capture = TestFrames.Capture();
        var corruptCrc = capture.Content.ToArray();
        corruptCrc[29] ^= 0x01;
        var trailing = capture.Content.ToArray().Concat(new byte[] { 0x00 }).ToArray();

        Assert.Throws<ArgumentException>(() =>
            ContentValidator.Validate(capture with { Content = corruptCrc }, new RelayOptions()));
        Assert.Throws<ArgumentException>(() =>
            ContentValidator.Validate(capture with { Content = trailing }, new RelayOptions()));
        Assert.Throws<ArgumentException>(() =>
            ContentValidator.Validate(capture with { Width = 2 }, new RelayOptions()));
    }

    [Fact]
    public void ContentValidator_RejectsExcessivePngChunkCount()
    {
        var capture = TestFrames.PngCaptureWithAncillaryChunks(4_095);

        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(capture, new RelayOptions()));
    }

    [Theory]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(20)]
    public void ContentValidator_RejectsTruncatedJpegStructure(int byteCount)
    {
        var capture = TestFrames.Capture(encoding: ImageEncoding.Jpeg);
        var truncated = capture with { Content = capture.Content[..byteCount] };

        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(truncated, new RelayOptions()));
    }

    [Fact]
    public void ContentValidator_RejectsJpegWithoutScanAndDimensionMismatch()
    {
        byte[] headerOnly =
        [
            0xff, 0xd8,
            0xff, 0xc0, 0x00, 0x0b, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
            0xff, 0xd9,
        ];
        var capture = TestFrames.Capture(encoding: ImageEncoding.Jpeg);

        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(
            capture with { Content = headerOnly },
            new RelayOptions()));
        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(
            capture with { Height = 2 },
            new RelayOptions()));
    }

    [Fact]
    public void ContentValidator_RejectsExcessiveJpegMarkers()
    {
        var bytes = new List<byte>(2 + (513 * 4) + 2) { 0xff, 0xd8 };
        for (var index = 0; index < 513; index++)
        {
            bytes.AddRange([0xff, 0xe0, 0x00, 0x02]);
        }

        bytes.AddRange([0xff, 0xd9]);
        var capture = TestFrames.Capture(encoding: ImageEncoding.Jpeg) with { Content = bytes.ToArray() };

        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(capture, new RelayOptions()));
    }

    [Theory]
    [InlineData("person@example.com")]
    [InlineData("/tmp/camera")]
    [InlineData("camera\nforged")]
    public void ContentValidator_RejectsNonCanonicalCallerIdentifiers(string identifier)
    {
        var capture = TestFrames.Capture() with { RequestId = identifier };

        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(capture, new RelayOptions()));
        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(
            TestFrames.Capture() with { StreamId = identifier },
            new RelayOptions()));
    }

    [Theory]
    [InlineData("person@example.com")]
    [InlineData("C:\\private\\capture.png")]
    [InlineData("source\nforged")]
    public void ContentValidator_RejectsArbitraryOrSensitiveCaptureSources(string source)
    {
        Assert.Throws<ArgumentException>(() => ContentValidator.Validate(
            TestFrames.Capture() with { Source = source },
            new RelayOptions()));
    }

    [Fact]
    public void RelayOptions_RejectsNonPositiveShutdownTimeout()
    {
        var error = Assert.Throws<ArgumentOutOfRangeException>(() =>
            new RelayOptions { ShutdownTimeout = TimeSpan.Zero }.Validate());

        Assert.Equal("ShutdownTimeout", error.ParamName);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(65)]
    public void RelayOptions_RejectsInvalidCleanupQuarantineBound(int value)
    {
        var error = Assert.Throws<ArgumentOutOfRangeException>(() =>
            new RelayOptions { MaximumRetainedCleanupTasks = value }.Validate());

        Assert.Equal("MaximumRetainedCleanupTasks", error.ParamName);
    }

    [Theory]
    [InlineData(0, 1)]
    [InlineData(1, 0)]
    [InlineData(11, 1)]
    [InlineData(1, 11)]
    public void ContentValidator_RejectsInvalidDimensions(int width, int height)
    {
        var options = new RelayOptions { MaximumWidth = 10, MaximumHeight = 10 };
        var capture = TestFrames.Capture() with { Width = width, Height = height };

        Assert.Throws<ArgumentOutOfRangeException>(() => ContentValidator.Validate(capture, options));
    }

    [Fact]
    public void ValidateRegion_RejectsSelectionOutsideSurface()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            ContentValidator.ValidateRegion(new CaptureRegion(90, 90, 20, 20), 100, 100));
    }
}
