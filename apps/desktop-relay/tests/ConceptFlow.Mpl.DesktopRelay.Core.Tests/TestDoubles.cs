// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Collections.Concurrent;
using System.Buffers.Binary;
using ConceptFlow.Mpl.DesktopRelay.Core;
using ConceptFlow.Mpl.V1;
using Google.Protobuf.WellKnownTypes;

namespace ConceptFlow.Mpl.DesktopRelay.Core.Tests;

internal sealed class FakeRelayClock : IRelayClock
{
    private long _ticks;

    public DateTimeOffset UtcNow { get; private set; } = new(2026, 8, 21, 0, 0, 0, TimeSpan.Zero);

    public long GetTimestamp() => _ticks;

    public TimeSpan GetElapsedTime(long startingTimestamp) => TimeSpan.FromTicks(_ticks - startingTimestamp);

    public ulong GetMonotonicNanoseconds() => checked((ulong)_ticks * 100UL);

    public void Advance(TimeSpan duration)
    {
        _ticks += duration.Ticks;
        UtcNow += duration;
    }
}

internal sealed class DelegateTransportFactory : IRelayTransportFactory
{
    private readonly Func<int, IRelayTransport> _factory;
    private int _count;

    public DelegateTransportFactory(Func<int, IRelayTransport> factory)
    {
        _factory = factory;
    }

    public int CreateCount => _count;

    public RelayOptions? LastOptions { get; private set; }

    public IRelayTransport Create(ValidatedEndpoint endpoint, RelayOptions options)
    {
        LastOptions = options;
        return _factory(++_count);
    }
}

internal sealed class FailingConnectTransport : IRelayTransport
{
    public Task<RelayNegotiation> ConnectAsync(
        string clientInstanceId,
        EphemeralIdentity identity,
        CancellationToken cancellationToken) =>
        Task.FromException<RelayNegotiation>(new IOException("synthetic connection failure token=must-not-leak"));

    public Task<PerceptionResult> ProcessFrameAsync(FramePayload frame, CancellationToken cancellationToken) =>
        throw new InvalidOperationException("Not connected.");

    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}

internal sealed class HangingProcessTransport : IRelayTransport
{
    private readonly TaskCompletionSource _cancellationObserved =
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    public bool CancellationObserved { get; private set; }

    public Task CancellationObservedTask => _cancellationObserved.Task;

    public Task<RelayNegotiation> ConnectAsync(
        string clientInstanceId,
        EphemeralIdentity identity,
        CancellationToken cancellationToken) =>
        Task.FromResult(TestNegotiations.Create(identity.SessionId));

    public async Task<PerceptionResult> ProcessFrameAsync(FramePayload frame, CancellationToken cancellationToken)
    {
        try
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            throw new InvalidOperationException("An infinite delay unexpectedly completed.");
        }
        catch (OperationCanceledException)
        {
            CancellationObserved = true;
            _cancellationObserved.TrySetResult();
            throw;
        }
    }

    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}

internal sealed class CancellationIgnoringTransport : IRelayTransport
{
    private readonly TaskCompletionSource<PerceptionResult> _processCompletion =
        new(TaskCreationOptions.RunContinuationsAsynchronously);
    private FramePayload? _frame;
    private int _disposeCount;

    public TaskCompletionSource Started { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public TaskCompletionSource Disposed { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public int DisposeCount => Volatile.Read(ref _disposeCount);

    public Task<RelayNegotiation> ConnectAsync(
        string clientInstanceId,
        EphemeralIdentity identity,
        CancellationToken cancellationToken) =>
        Task.FromResult(TestNegotiations.Create(identity.SessionId, maximumInFlight: 1));

    public Task<PerceptionResult> ProcessFrameAsync(FramePayload frame, CancellationToken cancellationToken)
    {
        _frame = frame;
        Started.TrySetResult();
        return _processCompletion.Task;
    }

    public void Complete() => _processCompletion.TrySetResult(TestFrames.Result(_frame!));

    public void Fail() => _processCompletion.TrySetException(new IOException("synthetic abandoned operation failure"));

    public ValueTask DisposeAsync()
    {
        Interlocked.Increment(ref _disposeCount);
        Disposed.TrySetResult();
        return ValueTask.CompletedTask;
    }
}

internal sealed class CancellationIgnoringDisposeTransport : IRelayTransport
{
    private readonly TaskCompletionSource _disposeCompletion =
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    public TaskCompletionSource DisposeStarted { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public Task<RelayNegotiation> ConnectAsync(
        string clientInstanceId,
        EphemeralIdentity identity,
        CancellationToken cancellationToken) =>
        Task.FromResult(TestNegotiations.Create(identity.SessionId));

    public Task<PerceptionResult> ProcessFrameAsync(FramePayload frame, CancellationToken cancellationToken) =>
        Task.FromResult(TestFrames.Result(frame));

    public ValueTask DisposeAsync()
    {
        DisposeStarted.TrySetResult();
        return new ValueTask(_disposeCompletion.Task);
    }

    public void CompleteDisposal() => _disposeCompletion.TrySetResult();
}

internal sealed class DelegateProcessTransport : IRelayTransport
{
    private readonly Func<string, RelayNegotiation> _negotiate;
    private readonly Func<FramePayload, int, CancellationToken, Task<PerceptionResult>> _process;
    private readonly ConcurrentDictionary<string, int> _requestCounts = new(StringComparer.Ordinal);
    private int _activeCount;
    private int _maxConcurrentCount;
    private int _processCount;

    public DelegateProcessTransport(
        Func<FramePayload, int, CancellationToken, Task<PerceptionResult>> process,
        Func<string, RelayNegotiation>? negotiate = null)
    {
        _process = process;
        _negotiate = negotiate ?? (sessionId => TestNegotiations.Create(sessionId));
    }

    public int ProcessCount => Volatile.Read(ref _processCount);

    public int ActiveCount => Volatile.Read(ref _activeCount);

    public int MaxConcurrentCount => Volatile.Read(ref _maxConcurrentCount);

    public int RequestCount(string requestId) =>
        _requestCounts.TryGetValue(requestId, out var count) ? count : 0;

    public Task<RelayNegotiation> ConnectAsync(
        string clientInstanceId,
        EphemeralIdentity identity,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromResult(_negotiate(identity.SessionId));
    }

    public async Task<PerceptionResult> ProcessFrameAsync(
        FramePayload frame,
        CancellationToken cancellationToken)
    {
        var callNumber = Interlocked.Increment(ref _processCount);
        _requestCounts.AddOrUpdate(frame.RequestId, 1, static (_, count) => count + 1);
        var activeCount = Interlocked.Increment(ref _activeCount);
        UpdateMaximum(activeCount);
        try
        {
            return await _process(frame, callNumber, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            Interlocked.Decrement(ref _activeCount);
        }
    }

    public ValueTask DisposeAsync() => ValueTask.CompletedTask;

    private void UpdateMaximum(int candidate)
    {
        var current = Volatile.Read(ref _maxConcurrentCount);
        while (candidate > current)
        {
            var observed = Interlocked.CompareExchange(ref _maxConcurrentCount, candidate, current);
            if (observed == current)
            {
                return;
            }

            current = observed;
        }
    }
}

internal sealed class RecordingCueOutputAdapter(
    bool delivers = true,
    bool supportsCancellation = true,
    bool supportsSupersession = true) : IRelayCueOutputAdapter
{
    private readonly ConcurrentQueue<PerceptionCue> _delivered = new();

    public IReadOnlyCollection<CueModality> SupportedProtocolModalities { get; } = [CueModality.Speech];

    public bool SupportsCancellation { get; } = supportsCancellation;

    public bool SupportsSupersession { get; } = supportsSupersession;

    public IReadOnlyCollection<PerceptionCue> Delivered => _delivered.ToArray();

    public bool CanDeliver(PerceptionCue cue) =>
        cue.Speech is not null || cue.Cancel is not null || cue.Supersede is not null;

    public Task<bool> DeliverAsync(PerceptionCue cue, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (delivers)
        {
            _delivered.Enqueue(cue.Clone());
        }

        return Task.FromResult(delivers);
    }
}

internal sealed class HangingCueOutputAdapter : IRelayCueOutputAdapter
{
    private readonly TaskCompletionSource<bool> _completion =
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    public IReadOnlyCollection<CueModality> SupportedProtocolModalities { get; } = [CueModality.Speech];

    public bool SupportsCancellation => false;

    public bool SupportsSupersession => false;

    public bool CanDeliver(PerceptionCue cue) => cue.Speech is not null;

    public Task<bool> DeliverAsync(PerceptionCue cue, CancellationToken cancellationToken) => _completion.Task;

    public void Complete() => _completion.TrySetResult(true);
}

internal sealed class RecordingStatusSink : IRelayStatusSink
{
    private readonly ConcurrentQueue<SafeStatus> _statuses = new();

    public IReadOnlyCollection<SafeStatus> Statuses => _statuses.ToArray();

    public void Report(SafeStatus status) => _statuses.Enqueue(status);
}

internal static class TestNegotiations
{
    public static RelayNegotiation Create(
        string sessionId,
        int maximumWidth = 4096,
        int maximumHeight = 4096,
        int maximumFrameBytes = 3 * 1024 * 1024,
        int maximumInFlight = 4,
        bool allowFrameDrop = false,
        params ImageEncoding[] imageEncodings)
    {
        var capabilities = new CapabilitySet
        {
            MaxWidth = checked((uint)maximumWidth),
            MaxHeight = checked((uint)maximumHeight),
            MaxFrameBytes = checked((ulong)maximumFrameBytes),
            SupportsCancellation = true,
            SupportsSupersession = true,
        };
        capabilities.ImageEncodings.Add(
            imageEncodings.Length == 0 ? [ImageEncoding.Png] : imageEncodings);
        capabilities.CueModalities.Add(CueModality.Speech);
        var qualityOfService = new QualityOfService
        {
            MaxInFlight = checked((uint)maximumInFlight),
            ResultDeadline = Duration.FromTimeSpan(TimeSpan.FromSeconds(3)),
            AllowFrameDrop = allowFrameDrop,
            MaxCuesPerResult = 8,
        };
        return new RelayNegotiation(sessionId, capabilities, qualityOfService);
    }
}

internal static class TestFrames
{
    private static readonly byte[] OnePixelPng = SyntheticCaptureAssets.CreateOnePixelPng();

    private static readonly byte[] OnePixelJpeg = Convert.FromBase64String(
        "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAEf/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABBQJ//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPwF//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAgEBPwF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAGPwJ//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPyF//9oADAMBAAIAAwAAABD/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/EH//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/EH//xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAE/EH//2Q==");

    public static FramePayload Frame(
        string requestId = "request-1",
        string sessionId = "session-1",
        string streamId = "stream-1",
        ulong frameId = 1,
        ulong captureNanoseconds = 100) => new()
        {
            RequestId = requestId,
            SessionId = sessionId,
            StreamId = streamId,
            FrameId = frameId,
            CaptureMonotonicTimestampNs = captureNanoseconds,
        };

    public static PerceptionResult Result(FramePayload frame) => new()
    {
        ResultId = $"result-{frame.RequestId}",
        RequestId = frame.RequestId,
        SessionId = frame.SessionId,
        StreamId = frame.StreamId,
        FrameId = frame.FrameId,
        CaptureMonotonicTimestampNs = frame.CaptureMonotonicTimestampNs,
    };

    public static PerceptionResult ResultWithCue(FramePayload frame)
    {
        var result = Result(frame);
        result.CompletedMonotonicTimestampNs = frame.CaptureMonotonicTimestampNs;
        result.Cues.Add(Cue(frame));
        return result;
    }

    public static PerceptionCue Cue(FramePayload frame) => new()
    {
        CueId = $"cue-{frame.FrameId}",
        FrameId = frame.FrameId,
        CreatedMonotonicTimestampNs = frame.CaptureMonotonicTimestampNs,
        TtlMs = 1_000,
        Category = CueCategory.Scene,
        Description = "Validated synthetic cue.",
        Confidence = 1,
        Speech = new Speech
        {
            Text = "Validated synthetic speech.",
            LanguageTag = "en",
        },
    };

    public static CaptureSubmission Capture(
        bool consent = true,
        ImageEncoding encoding = ImageEncoding.Png) => new(
        Source: "user-selected-region",
        Content: encoding == ImageEncoding.Jpeg ? OnePixelJpeg.ToArray() : OnePixelPng.ToArray(),
        Width: 1,
        Height: 1,
        Encoding: encoding,
        MediaType: encoding == ImageEncoding.Jpeg ? "image/jpeg" : "image/png",
        ConsentGranted: consent,
        Synthetic: true);

    public static CaptureSubmission PngCapture(int width, int height)
    {
        var bytes = OnePixelPng.ToArray();
        BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(16, 4), checked((uint)width));
        BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(20, 4), checked((uint)height));
        BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(29, 4), CalculateCrc32(bytes.AsSpan(12, 17)));
        return Capture() with { Content = bytes, Width = width, Height = height };
    }

    public static CaptureSubmission PngCaptureWithAncillaryChunks(int count = 1)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(count);
        var original = OnePixelPng;
        var iendOffset = original.Length - 12;
        var bytes = new byte[original.Length + (12 * count)];
        original.AsSpan(0, iendOffset).CopyTo(bytes);
        for (var index = 0; index < count; index++)
        {
            var chunkOffset = iendOffset + (index * 12);
            "tEXt"u8.CopyTo(bytes.AsSpan(chunkOffset + 4, 4));
            BinaryPrimitives.WriteUInt32BigEndian(
                bytes.AsSpan(chunkOffset + 8, 4),
                CalculateCrc32(bytes.AsSpan(chunkOffset + 4, 4)));
        }

        original.AsSpan(iendOffset).CopyTo(bytes.AsSpan(iendOffset + (12 * count)));
        return Capture() with { Content = bytes };
    }

    private static uint CalculateCrc32(ReadOnlySpan<byte> data)
    {
        var crc = uint.MaxValue;
        foreach (var value in data)
        {
            crc ^= value;
            for (var bit = 0; bit < 8; bit++)
            {
                crc = (crc >> 1) ^ ((crc & 1) == 0 ? 0 : 0xedb88320u);
            }
        }

        return ~crc;
    }
}
