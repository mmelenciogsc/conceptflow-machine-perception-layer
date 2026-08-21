// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public enum RelaySessionState
{
    Idle,
    Connecting,
    Negotiating,
    Active,
    ReconnectBackoff,
    Stopping,
    Closed,
    Failed,
}

public enum QueueOverflowPolicy
{
    RejectNewest,
    DropOldest,
}

public sealed record RelayOptions
{
    public int QueueCapacity { get; init; } = 4;
    public QueueOverflowPolicy QueueOverflowPolicy { get; init; } = QueueOverflowPolicy.RejectNewest;
    public int MaximumMessageBytes { get; init; } = 4 * 1024 * 1024;
    public int MaximumFrameBytes { get; init; } = 3 * 1024 * 1024;
    public int MaximumWidth { get; init; } = 4096;
    public int MaximumHeight { get; init; } = 4096;
    public int MaximumCuesPerResult { get; init; } = 8;
    public TimeSpan ConnectTimeout { get; init; } = TimeSpan.FromSeconds(8);
    public TimeSpan RequestTimeout { get; init; } = TimeSpan.FromSeconds(3);
    public TimeSpan OutputTimeout { get; init; } = TimeSpan.FromSeconds(1);
    public TimeSpan ShutdownTimeout { get; init; } = TimeSpan.FromSeconds(2);
    public int MaximumRetainedCleanupTasks { get; init; } = 4;
    public TimeSpan MaximumResultAge { get; init; } = TimeSpan.FromSeconds(5);
    public int MaximumReconnectAttempts { get; init; } = 4;
    public TimeSpan InitialReconnectDelay { get; init; } = TimeSpan.FromMilliseconds(250);
    public TimeSpan MaximumReconnectDelay { get; init; } = TimeSpan.FromSeconds(4);
    public bool AllowInsecureLoopbackForDevelopment { get; init; }
    public IReadOnlyList<CueModality> SupportedCueModalities { get; init; } = Array.Empty<CueModality>();
    public bool SupportsCueCancellation { get; init; }
    public bool SupportsCueSupersession { get; init; }

    public void Validate()
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(QueueCapacity, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(QueueCapacity, 256);
        ArgumentOutOfRangeException.ThrowIfLessThan(MaximumFrameBytes, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(MaximumMessageBytes, MaximumFrameBytes);
        ArgumentOutOfRangeException.ThrowIfLessThan(MaximumWidth, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(MaximumHeight, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(MaximumCuesPerResult, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(MaximumCuesPerResult, 64);
        ArgumentOutOfRangeException.ThrowIfLessThan(MaximumRetainedCleanupTasks, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(MaximumRetainedCleanupTasks, 64);
        ArgumentOutOfRangeException.ThrowIfLessThan(MaximumReconnectAttempts, 1);
        ArgumentNullException.ThrowIfNull(SupportedCueModalities);
        var modalities = new HashSet<CueModality>();
        foreach (var modality in SupportedCueModalities)
        {
            if (modality == CueModality.Unspecified || !Enum.IsDefined(modality) || !modalities.Add(modality))
            {
                throw new ArgumentException("Advertised cue modalities must be unique, concrete protocol values.");
            }
        }
        if (ShutdownTimeout <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(ShutdownTimeout), "The shutdown timeout must be positive.");
        }

        if (ConnectTimeout <= TimeSpan.Zero || RequestTimeout <= TimeSpan.Zero ||
            OutputTimeout <= TimeSpan.Zero || MaximumResultAge <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(ConnectTimeout), "Timeouts and maximum result age must be positive.");
        }

        if (InitialReconnectDelay < TimeSpan.Zero || MaximumReconnectDelay < InitialReconnectDelay)
        {
            throw new ArgumentOutOfRangeException(nameof(InitialReconnectDelay), "Reconnect delays are invalid.");
        }
    }
}

public sealed record RelaySessionSnapshot(
    RelaySessionState State,
    string? SessionId,
    int Attempt,
    SafeStatus Status);

public sealed record CaptureSubmission(
    string Source,
    ReadOnlyMemory<byte> Content,
    int Width,
    int Height,
    ImageEncoding Encoding,
    string MediaType,
    bool ConsentGranted,
    bool Synthetic,
    string StreamId = "desktop",
    ulong FrameId = 1,
    string? RequestId = null);

public sealed record RelayOutcome(
    bool Accepted,
    string RequestId,
    string ResultId,
    string Text,
    CorrelationDecision Correlation,
    PerceptionResult? Result,
    RelayDeliveryStatus Delivery = RelayDeliveryStatus.NotRequired);

public enum RelayDeliveryStatus
{
    NotRequired,
    Delivered,
    ControlApplied,
    Failed,
}

public interface IRelayStatusSink
{
    void Report(SafeStatus status);
}

public sealed class NullRelayStatusSink : IRelayStatusSink
{
    public static NullRelayStatusSink Instance { get; } = new();

    private NullRelayStatusSink()
    {
    }

    public void Report(SafeStatus status)
    {
    }
}
