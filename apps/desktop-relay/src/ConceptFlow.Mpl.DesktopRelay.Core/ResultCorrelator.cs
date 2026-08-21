// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public enum CorrelationRejection
{
    None,
    UnknownRequest,
    SessionMismatch,
    StreamMismatch,
    FrameMismatch,
    CaptureTimestampMismatch,
    Stale,
    OutOfOrder,
    InvalidCue,
    InvalidResult,
}

public sealed record CorrelationDecision(bool Accepted, CorrelationRejection Rejection, string Message);

public sealed class ResultCorrelator
{
    private sealed record Pending(
        string SessionId,
        string StreamId,
        ulong FrameId,
        ulong CaptureTimestampNanoseconds,
        long RegisteredTimestamp);

    private readonly IRelayClock _clock;
    private readonly TimeSpan _maximumAge;
    private readonly int _maximumPending;
    private readonly int _maximumTrackedStreams;
    private readonly Dictionary<string, Pending> _pending = new(StringComparer.Ordinal);
    private readonly LinkedList<string> _order = new();
    private readonly Dictionary<(string Session, string Stream), ulong> _lastAccepted = new();
    private readonly LinkedList<(string Session, string Stream)> _lastAcceptedOrder = new();
    private readonly Dictionary<(string Session, string Stream), int> _pendingStreams = new();
    private readonly object _gate = new();

    public ResultCorrelator(
        IRelayClock clock,
        TimeSpan maximumAge,
        int maximumPending,
        int? maximumTrackedStreams = null)
    {
        ArgumentNullException.ThrowIfNull(clock);
        if (maximumAge <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(maximumAge));
        }

        ArgumentOutOfRangeException.ThrowIfLessThan(maximumPending, 1);
        var trackedStreamCapacity = maximumTrackedStreams ?? maximumPending;
        if (trackedStreamCapacity < maximumPending)
        {
            throw new ArgumentOutOfRangeException(
                nameof(maximumTrackedStreams),
                "Tracked-stream capacity must be at least the pending-request capacity so active streams remain pinned.");
        }

        _clock = clock;
        _maximumAge = maximumAge;
        _maximumPending = maximumPending;
        _maximumTrackedStreams = trackedStreamCapacity;
    }

    public string? Register(FramePayload frame)
    {
        ArgumentNullException.ThrowIfNull(frame);
        ProtocolValueValidator.ValidateIdentifier(frame.RequestId, "request_id");
        ProtocolValueValidator.ValidateIdentifier(frame.SessionId, "session_id");
        ProtocolValueValidator.ValidateIdentifier(frame.StreamId, "stream_id");

        lock (_gate)
        {
            if (_pending.ContainsKey(frame.RequestId))
            {
                throw new InvalidOperationException("The request identifier is already pending.");
            }

            string? evicted = null;
            if (_pending.Count >= _maximumPending)
            {
                evicted = _order.First!.Value;
                _order.RemoveFirst();
                var evictedPending = _pending[evicted];
                _pending.Remove(evicted);
                Unpin(evictedPending);
            }

            var pending = new Pending(
                frame.SessionId,
                frame.StreamId,
                frame.FrameId,
                frame.CaptureMonotonicTimestampNs,
                _clock.GetTimestamp());
            _pending.Add(frame.RequestId, pending);
            _order.AddLast(frame.RequestId);
            Pin(pending);
            return evicted;
        }
    }

    public bool Cancel(string requestId)
    {
        lock (_gate)
        {
            if (_pending.Remove(requestId, out var pending))
            {
                _order.Remove(requestId);
                Unpin(pending);
                return true;
            }

            return false;
        }
    }

    public CorrelationDecision Accept(PerceptionResult result)
    {
        ArgumentNullException.ThrowIfNull(result);
        if (!ProtocolValueValidator.IsIdentifier(result.RequestId) ||
            !ProtocolValueValidator.IsIdentifier(result.SessionId) ||
            !ProtocolValueValidator.IsIdentifier(result.StreamId))
        {
            return Reject(CorrelationRejection.InvalidResult, "Result identifiers are not canonical opaque values.");
        }

        lock (_gate)
        {
            if (!_pending.Remove(result.RequestId, out var expected))
            {
                return Reject(CorrelationRejection.UnknownRequest, "Unknown, cancelled, evicted, or already completed request.");
            }

            _order.Remove(result.RequestId);
            Unpin(expected);

            if (!string.Equals(result.SessionId, expected.SessionId, StringComparison.Ordinal))
            {
                return Reject(CorrelationRejection.SessionMismatch, "Result session does not match the request.");
            }

            if (!string.Equals(result.StreamId, expected.StreamId, StringComparison.Ordinal))
            {
                return Reject(CorrelationRejection.StreamMismatch, "Result stream does not match the request.");
            }

            if (result.FrameId != expected.FrameId)
            {
                return Reject(CorrelationRejection.FrameMismatch, "Result frame does not match the request.");
            }

            if (result.CaptureMonotonicTimestampNs != expected.CaptureTimestampNanoseconds)
            {
                return Reject(CorrelationRejection.CaptureTimestampMismatch, "Result capture timestamp does not match the request.");
            }

            if (_clock.GetElapsedTime(expected.RegisteredTimestamp) > _maximumAge)
            {
                return Reject(CorrelationRejection.Stale, "Result exceeded its maximum accepted age.");
            }

            var key = (expected.SessionId, expected.StreamId);
            if (_lastAccepted.TryGetValue(key, out var lastFrame) && result.FrameId <= lastFrame)
            {
                return Reject(CorrelationRejection.OutOfOrder, "Result is not newer than the last accepted frame.");
            }

            TrackAccepted(key, result.FrameId);
            return new CorrelationDecision(true, CorrelationRejection.None, "Correlated result accepted.");
        }
    }

    private void TrackAccepted((string Session, string Stream) key, ulong frameId)
    {
        if (_lastAccepted.ContainsKey(key))
        {
            _lastAccepted[key] = frameId;
            _lastAcceptedOrder.Remove(key);
            _lastAcceptedOrder.AddLast(key);
            return;
        }

        if (_lastAccepted.Count == _maximumTrackedStreams)
        {
            var candidate = _lastAcceptedOrder.First;
            while (candidate is not null && _pendingStreams.ContainsKey(candidate.Value))
            {
                candidate = candidate.Next;
            }

            if (candidate is null)
            {
                throw new InvalidOperationException("No unpinned accepted stream is available for deterministic eviction.");
            }

            _lastAccepted.Remove(candidate.Value);
            _lastAcceptedOrder.Remove(candidate);
        }

        _lastAccepted.Add(key, frameId);
        _lastAcceptedOrder.AddLast(key);
    }

    private void Pin(Pending pending)
    {
        var key = (pending.SessionId, pending.StreamId);
        _pendingStreams.TryGetValue(key, out var pendingCount);
        _pendingStreams[key] = checked(pendingCount + 1);
    }

    private void Unpin(Pending pending)
    {
        var key = (pending.SessionId, pending.StreamId);
        var pendingCount = _pendingStreams[key];
        if (pendingCount == 1)
        {
            _pendingStreams.Remove(key);
        }
        else
        {
            _pendingStreams[key] = pendingCount - 1;
        }
    }

    private static CorrelationDecision Reject(CorrelationRejection rejection, string message) =>
        new(false, rejection, message);
}
