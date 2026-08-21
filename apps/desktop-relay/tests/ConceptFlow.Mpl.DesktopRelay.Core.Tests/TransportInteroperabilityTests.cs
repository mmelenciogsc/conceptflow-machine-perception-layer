// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.DesktopRelay.Core;
using ConceptFlow.Mpl.V1;
using Google.Protobuf;
using Google.Protobuf.WellKnownTypes;

namespace ConceptFlow.Mpl.DesktopRelay.Core.Tests;

public sealed class TransportInteroperabilityTests
{
    [Fact]
    public void PythonGeneratedCanonicalVectors_ParseAndRoundTripByteExactly()
    {
        var vectors = File.ReadAllLines(Path.Combine(
                AppContext.BaseDirectory,
                "fixtures",
                "protocol_vectors.properties"))
            .Where(line => line.Length > 0 && !line.StartsWith('#'))
            .Select(line => line.Split('=', 2))
            .ToDictionary(parts => parts[0], parts => parts[1], StringComparer.Ordinal);
        var frameBytes = Convert.FromBase64String(vectors["frame_payload_base64"]);
        var resultBytes = Convert.FromBase64String(vectors["perception_result_base64"]);

        var frame = FramePayload.Parser.ParseFrom(frameBytes);
        var result = PerceptionResult.Parser.ParseFrom(resultBytes);

        Assert.Equal("1", vectors["schema_version"]);
        Assert.Equal("interop-request-1", frame.RequestId);
        Assert.Equal(ImageEncoding.Png, frame.Image.Encoding);
        Assert.Equal(new byte[] { 0x89, 0x50, 0x4e, 0x47 }, frame.FrameData.Span[..4].ToArray());
        Assert.Equal(frame.RequestId, result.RequestId);
        Assert.Equal(frame.FrameId, result.FrameId);
        Assert.Equal("Object ahead", Assert.Single(result.Cues).Speech.Text);
        Assert.Equal(frameBytes, frame.ToByteArray());
        Assert.Equal(resultBytes, result.ToByteArray());
    }

    [Theory]
    [InlineData("person@example.com")]
    [InlineData("/tmp/session")]
    [InlineData("session\nforged")]
    public void GrpcNegotiation_RejectsNonCanonicalCallerSessionIdentifier(string sessionId)
    {
        var identity = new EphemeralIdentity { SessionId = sessionId };

        Assert.Throws<ArgumentException>(() => GrpcRelayTransport.CreateNegotiationRequest(
            "desktop-interop",
            identity,
            new RelayOptions()));
    }

    [Fact]
    public void GrpcNegotiation_SerializesExactPngJpegAndTruthfulCueCapabilities()
    {
        var options = new RelayOptions
        {
            SupportedCueModalities = [CueModality.Speech],
            SupportsCueCancellation = true,
            SupportsCueSupersession = true,
        };
        var identity = new EphemeralIdentity
        {
            SessionId = "session-interop",
            Nonce = ByteString.CopyFrom(new byte[16]),
            ExpiresAt = Timestamp.FromDateTime(DateTime.UnixEpoch.AddMinutes(10)),
        };

        var encoded = GrpcRelayTransport.CreateNegotiationRequest(
            "desktop-interop",
            identity,
            options).ToByteArray();
        var decoded = NegotiateRequest.Parser.ParseFrom(encoded);

        Assert.Equal([ImageEncoding.Jpeg, ImageEncoding.Png], decoded.Capabilities.ImageEncodings);
        Assert.Equal([CueModality.Speech], decoded.Capabilities.CueModalities);
        Assert.True(decoded.Capabilities.SupportsCancellation);
        Assert.True(decoded.Capabilities.SupportsSupersession);
        Assert.Equal((uint)options.MaximumCuesPerResult, decoded.RequestedQos.MaxCuesPerResult);
    }

    [Fact]
    public void CanonicalNegotiationResponse_RoundTripsAndAcceptsBothCompressedFormats()
    {
        var response = new NegotiateResponse
        {
            Identity = new EphemeralIdentity { SessionId = "session-canonical" },
            Capabilities = new CapabilitySet
            {
                MaxWidth = 4096,
                MaxHeight = 4096,
                MaxFrameBytes = 3 * 1024 * 1024,
            },
            AcceptedQos = new QualityOfService
            {
                MaxInFlight = 4,
                MaxCuesPerResult = 8,
                ResultDeadline = Duration.FromTimeSpan(TimeSpan.FromSeconds(3)),
            },
        };
        response.Capabilities.ImageEncodings.Add(ImageEncoding.Jpeg);
        response.Capabilities.ImageEncodings.Add(ImageEncoding.Png);
        response.Capabilities.CueModalities.Add(CueModality.Speech);
        var decoded = NegotiateResponse.Parser.ParseFrom(response.ToByteArray());
        var options = new RelayOptions { SupportedCueModalities = [CueModality.Speech] };

        var policy = NegotiatedRelayPolicy.Create(
            new RelayNegotiation(decoded.Identity.SessionId, decoded.Capabilities, decoded.AcceptedQos),
            options);

        Assert.Equal([ImageEncoding.Jpeg, ImageEncoding.Png], policy.SupportedImageEncodings);
        Assert.Equal([CueModality.Speech], policy.SupportedCueModalities);
    }

    [Theory]
    [InlineData("person@example.com")]
    [InlineData("C:\\private\\session")]
    [InlineData("session\nforged")]
    public void NegotiatedPolicy_RejectsNonCanonicalServiceSessionIdentifier(string sessionId)
    {
        var negotiation = TestNegotiations.Create(sessionId);

        Assert.Throws<InvalidOperationException>(() =>
            NegotiatedRelayPolicy.Create(negotiation, new RelayOptions()));
    }

    [Fact]
    public async Task RelaySession_DerivesAdvertisedCapabilitiesFromConcreteOutputAdapter()
    {
        var transport = new DelegateProcessTransport(
            (frame, _, _) => Task.FromResult(TestFrames.Result(frame)));
        var factory = new DelegateTransportFactory(_ => transport);
        await using var session = new RelaySession(
            factory,
            cueOutput: new RecordingCueOutputAdapter());

        await session.ConnectAsync("https://localhost:7443");

        var options = Assert.IsType<RelayOptions>(factory.LastOptions);
        Assert.Equal([CueModality.Speech], options.SupportedCueModalities);
        Assert.True(options.SupportsCueCancellation);
        Assert.True(options.SupportsCueSupersession);
    }

    [Fact]
    public async Task ActualWpfPngCapture_RoundTripsThroughCanonicalFrameAndResultSerialization()
    {
        FramePayload? wireFrame = null;
        var transport = new DelegateProcessTransport((frame, _, _) =>
        {
            wireFrame = FramePayload.Parser.ParseFrom(frame.ToByteArray());
            return Task.FromResult(PerceptionResult.Parser.ParseFrom(TestFrames.Result(wireFrame).ToByteArray()));
        });
        await using var session = new RelaySession(new DelegateTransportFactory(_ => transport));
        await session.ConnectAsync("https://localhost:7443");

        var outcome = await session.SubmitAsync(
            TestFrames.Capture() with { Source = "synthetic-demo" });

        Assert.True(outcome.Accepted);
        Assert.NotNull(wireFrame);
        Assert.Equal(ImageEncoding.Png, wireFrame.Image.Encoding);
        Assert.Equal("image/png", wireFrame.Image.MediaType);
        Assert.Equal((uint)1, wireFrame.Image.Width);
        Assert.Equal((uint)1, wireFrame.Image.Height);
        Assert.True(wireFrame.FrameData.Span.StartsWith(new byte[] { 0x89, 0x50, 0x4e, 0x47 }));
    }
}
