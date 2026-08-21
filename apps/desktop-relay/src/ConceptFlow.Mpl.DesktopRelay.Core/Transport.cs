// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.V1;
using Google.Protobuf.WellKnownTypes;
using Grpc.Net.Client;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public sealed record RelayNegotiation(string SessionId, CapabilitySet Capabilities, QualityOfService QualityOfService);

public interface IRelayTransport : IAsyncDisposable
{
    Task<RelayNegotiation> ConnectAsync(
        string clientInstanceId,
        EphemeralIdentity identity,
        CancellationToken cancellationToken);

    Task<PerceptionResult> ProcessFrameAsync(FramePayload frame, CancellationToken cancellationToken);
}

public interface IRelayTransportFactory
{
    IRelayTransport Create(ValidatedEndpoint endpoint, RelayOptions options);
}

public sealed class GrpcRelayTransportFactory : IRelayTransportFactory
{
    public IRelayTransport Create(ValidatedEndpoint endpoint, RelayOptions options) =>
        new GrpcRelayTransport(endpoint, options);
}

public sealed class GrpcRelayTransport : IRelayTransport
{
    private readonly RelayOptions _options;
    private readonly GrpcChannel _channel;
    private readonly PerceptionService.PerceptionServiceClient _client;

    public GrpcRelayTransport(ValidatedEndpoint endpoint, RelayOptions options)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        ArgumentNullException.ThrowIfNull(options);
        options.Validate();
        if (!endpoint.UsesTls && !endpoint.IsDevelopmentLoopback)
        {
            throw new InvalidOperationException("Insecure gRPC transport is restricted to explicitly enabled loopback development.");
        }

        _options = options;
        var handler = new SocketsHttpHandler
        {
            EnableMultipleHttp2Connections = true,
            PooledConnectionIdleTimeout = TimeSpan.FromMinutes(2),
            ConnectTimeout = options.ConnectTimeout,
        };
        _channel = GrpcChannel.ForAddress(
            endpoint.Uri,
            new GrpcChannelOptions
            {
                HttpHandler = handler,
                MaxReceiveMessageSize = options.MaximumMessageBytes,
                MaxSendMessageSize = options.MaximumMessageBytes,
                DisposeHttpClient = true,
            });
        _client = new PerceptionService.PerceptionServiceClient(_channel);
    }

    public async Task<RelayNegotiation> ConnectAsync(
        string clientInstanceId,
        EphemeralIdentity identity,
        CancellationToken cancellationToken)
    {
        var request = CreateNegotiationRequest(clientInstanceId, identity, _options);

        var response = await _client.NegotiateAsync(
            request,
            deadline: DateTime.UtcNow.Add(_options.ConnectTimeout),
            cancellationToken: cancellationToken).ResponseAsync.ConfigureAwait(false);
        if (response.Error is { Code: not ErrorCode.Unspecified })
        {
            throw new InvalidOperationException("The perception service rejected capability negotiation.");
        }

        if (response.Identity is not { SessionId: var sessionId } ||
            !ProtocolValueValidator.IsIdentifier(sessionId))
        {
            throw new InvalidOperationException("Negotiation returned no valid session identifier.");
        }

        return new RelayNegotiation(sessionId, response.Capabilities, response.AcceptedQos);
    }

    internal static NegotiateRequest CreateNegotiationRequest(
        string clientInstanceId,
        EphemeralIdentity identity,
        RelayOptions options)
    {
        ArgumentNullException.ThrowIfNull(identity);
        ArgumentNullException.ThrowIfNull(options);
        ProtocolValueValidator.ValidateIdentifier(clientInstanceId, "client_instance_id");
        ProtocolValueValidator.ValidateIdentifier(identity.SessionId, "identity.session_id");
        options.Validate();
        var request = new NegotiateRequest
        {
            ClientInstanceId = clientInstanceId,
            Identity = identity.Clone(),
            Capabilities = new CapabilitySet
            {
                MaxWidth = (uint)options.MaximumWidth,
                MaxHeight = (uint)options.MaximumHeight,
                MaxFrameBytes = (ulong)options.MaximumFrameBytes,
                SupportsCancellation = options.SupportsCueCancellation,
                SupportsSupersession = options.SupportsCueSupersession,
            },
            RequestedQos = new QualityOfService
            {
                MaxInFlight = (uint)options.QueueCapacity,
                TargetFramesPerSecond = 1,
                ResultDeadline = Duration.FromTimeSpan(options.RequestTimeout),
                AllowFrameDrop = options.QueueOverflowPolicy == QueueOverflowPolicy.DropOldest,
                MaxCuesPerResult = (uint)options.MaximumCuesPerResult,
            },
        };
        request.SupportedVersions.Add(new ProtocolVersion { Major = 1, Minor = 0, Patch = 0 });
        request.Capabilities.ImageEncodings.Add(ImageEncoding.Jpeg);
        request.Capabilities.ImageEncodings.Add(ImageEncoding.Png);
        request.Capabilities.CueModalities.Add(options.SupportedCueModalities);
        return request;
    }

    public async Task<PerceptionResult> ProcessFrameAsync(FramePayload frame, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(frame);
        if (frame.CalculateSize() > _options.MaximumMessageBytes)
        {
            throw new ArgumentOutOfRangeException(nameof(frame), "Serialized frame exceeds the configured message limit.");
        }

        return await _client.ProcessFrameAsync(
            frame,
            deadline: DateTime.UtcNow.Add(_options.RequestTimeout),
            cancellationToken: cancellationToken).ResponseAsync.ConfigureAwait(false);
    }

    public ValueTask DisposeAsync()
    {
        _channel.Dispose();
        return ValueTask.CompletedTask;
    }
}

public sealed class InProcessRelayTransportFactory : IRelayTransportFactory
{
    private readonly Func<IRelayTransport>? _factory;

    public InProcessRelayTransportFactory(Func<IRelayTransport>? factory = null)
    {
        _factory = factory;
    }

    public IRelayTransport Create(ValidatedEndpoint endpoint, RelayOptions options) =>
        _factory?.Invoke() ?? new InProcessRelayTransport(options);
}

public sealed class InProcessRelayTransport : IRelayTransport
{
    private readonly RelayOptions _options;
    private string? _sessionId;

    public InProcessRelayTransport(RelayOptions? options = null)
    {
        _options = options ?? new RelayOptions();
        _options.Validate();
    }

    public Task<RelayNegotiation> ConnectAsync(
        string clientInstanceId,
        EphemeralIdentity identity,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ProtocolValueValidator.ValidateIdentifier(clientInstanceId, "client_instance_id");
        ProtocolValueValidator.ValidateIdentifier(identity.SessionId, "identity.session_id");

        _sessionId = identity.SessionId;
        var capabilities = new CapabilitySet
        {
            MaxWidth = 4096,
            MaxHeight = 4096,
            MaxFrameBytes = 3 * 1024 * 1024,
            SupportsCancellation = _options.SupportsCueCancellation,
            SupportsSupersession = _options.SupportsCueSupersession,
        };
        capabilities.ImageEncodings.Add(ImageEncoding.Jpeg);
        capabilities.ImageEncodings.Add(ImageEncoding.Png);
        capabilities.CueModalities.Add(_options.SupportedCueModalities);
        var quality = new QualityOfService
        {
            MaxInFlight = (uint)Math.Min(4, _options.QueueCapacity),
            TargetFramesPerSecond = 1,
            ResultDeadline = Duration.FromTimeSpan(
                _options.RequestTimeout < TimeSpan.FromSeconds(3)
                    ? _options.RequestTimeout
                    : TimeSpan.FromSeconds(3)),
            AllowFrameDrop = false,
            MaxCuesPerResult = (uint)Math.Min(4, _options.MaximumCuesPerResult),
        };
        return Task.FromResult(new RelayNegotiation(identity.SessionId, capabilities, quality));
    }

    public async Task<PerceptionResult> ProcessFrameAsync(FramePayload frame, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        await Task.Yield();
        cancellationToken.ThrowIfCancellationRequested();
        if (!string.Equals(frame.SessionId, _sessionId, StringComparison.Ordinal))
        {
            throw new InvalidOperationException("Frame session does not match the negotiated session.");
        }

        var result = new PerceptionResult
        {
            ResultId = $"result-{frame.RequestId}",
            RequestId = frame.RequestId,
            SessionId = frame.SessionId,
            StreamId = frame.StreamId,
            FrameId = frame.FrameId,
            CaptureMonotonicTimestampNs = frame.CaptureMonotonicTimestampNs,
            CompletedMonotonicTimestampNs = frame.CaptureMonotonicTimestampNs,
            Provenance = new Provenance
            {
                Component = "desktop-relay-in-process-demo",
                ComponentVersion = "1",
                WorkerId = "deterministic-mock",
                ModelId = "none",
                Synthetic = true,
            },
        };
        var cue = new PerceptionCue
        {
            CueId = $"cue-{frame.FrameId}",
            FrameId = frame.FrameId,
            CreatedMonotonicTimestampNs = result.CompletedMonotonicTimestampNs,
            TtlMs = 2_000,
            Category = CueCategory.Scene,
            Description = $"Synthetic demo frame {frame.FrameId} accepted; {frame.FrameData.Length} bytes inspected.",
            Confidence = 1.0,
            Priority = 1,
            Direction = Direction.Ahead,
            Urgency = Urgency.Low,
            Provenance = new Provenance
            {
                Component = "desktop-relay-in-process-demo",
                ComponentVersion = "1",
                WorkerId = "deterministic-mock",
                ModelId = "none",
                Synthetic = true,
            },
        };
        if (_options.SupportedCueModalities.Contains(CueModality.Speech))
        {
            cue.Speech = new Speech
            {
                Text = "Synthetic demo cue ready.",
                LanguageTag = "en",
                Interrupt = false,
            };
        }

        result.Cues.Add(cue);
        return result;
    }

    public ValueTask DisposeAsync()
    {
        _sessionId = null;
        return ValueTask.CompletedTask;
    }
}
