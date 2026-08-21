// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.DesktopRelay.Core;
using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core.Tests;

public sealed class CorrelationTests
{
    [Fact]
    public void Accept_RejectsMismatchedSession()
    {
        var clock = new FakeRelayClock();
        var correlator = new ResultCorrelator(clock, TimeSpan.FromSeconds(2), 4);
        var frame = TestFrames.Frame();
        correlator.Register(frame);
        var result = TestFrames.Result(frame);
        result.SessionId = "wrong-session";

        var decision = correlator.Accept(result);

        Assert.False(decision.Accepted);
        Assert.Equal(CorrelationRejection.SessionMismatch, decision.Rejection);
    }

    [Fact]
    public void Accept_ConsumesMismatchedRequestExactlyOnce()
    {
        var correlator = new ResultCorrelator(new FakeRelayClock(), TimeSpan.FromSeconds(2), 4);
        var frame = TestFrames.Frame();
        correlator.Register(frame);
        var mismatched = TestFrames.Result(frame);
        mismatched.StreamId = "wrong-stream";

        var mismatch = correlator.Accept(mismatched);
        var retry = correlator.Accept(TestFrames.Result(frame));

        Assert.Equal(CorrelationRejection.StreamMismatch, mismatch.Rejection);
        Assert.Equal(CorrelationRejection.UnknownRequest, retry.Rejection);
    }

    [Fact]
    public void Accept_RejectsStaleResult()
    {
        var clock = new FakeRelayClock();
        var correlator = new ResultCorrelator(clock, TimeSpan.FromSeconds(1), 4);
        var frame = TestFrames.Frame();
        correlator.Register(frame);
        clock.Advance(TimeSpan.FromSeconds(2));

        var decision = correlator.Accept(TestFrames.Result(frame));

        Assert.False(decision.Accepted);
        Assert.Equal(CorrelationRejection.Stale, decision.Rejection);
    }

    [Fact]
    public void Accept_RejectsOutOfOrderFrameWithinStream()
    {
        var clock = new FakeRelayClock();
        var correlator = new ResultCorrelator(clock, TimeSpan.FromSeconds(2), 4);
        var newer = TestFrames.Frame("request-2", frameId: 2, captureNanoseconds: 200);
        correlator.Register(newer);
        Assert.True(correlator.Accept(TestFrames.Result(newer)).Accepted);
        var older = TestFrames.Frame("request-1", frameId: 1, captureNanoseconds: 100);
        correlator.Register(older);

        var decision = correlator.Accept(TestFrames.Result(older));

        Assert.False(decision.Accepted);
        Assert.Equal(CorrelationRejection.OutOfOrder, decision.Rejection);
    }

    [Fact]
    public void Accept_RejectsCancelledAndDuplicateResults()
    {
        var clock = new FakeRelayClock();
        var correlator = new ResultCorrelator(clock, TimeSpan.FromSeconds(2), 4);
        var frame = TestFrames.Frame();
        correlator.Register(frame);
        Assert.True(correlator.Cancel(frame.RequestId));

        var cancelled = correlator.Accept(TestFrames.Result(frame));
        var duplicate = correlator.Accept(TestFrames.Result(frame));

        Assert.Equal(CorrelationRejection.UnknownRequest, cancelled.Rejection);
        Assert.Equal(CorrelationRejection.UnknownRequest, duplicate.Rejection);
    }

    [Fact]
    public void Register_EvictsOldestAtBound()
    {
        var correlator = new ResultCorrelator(new FakeRelayClock(), TimeSpan.FromSeconds(2), 2);
        correlator.Register(TestFrames.Frame("one"));
        correlator.Register(TestFrames.Frame("two", frameId: 2));

        var evicted = correlator.Register(TestFrames.Frame("three", frameId: 3));

        Assert.Equal("one", evicted);
    }

    [Fact]
    public void Register_RejectsDuplicateWithoutConsumingOriginal()
    {
        var correlator = new ResultCorrelator(new FakeRelayClock(), TimeSpan.FromSeconds(2), 2);
        var frame = TestFrames.Frame();
        correlator.Register(frame);

        Assert.Throws<InvalidOperationException>(() => correlator.Register(frame));
        Assert.True(correlator.Accept(TestFrames.Result(frame)).Accepted);
    }

    [Fact]
    public void Accept_EvictsLeastRecentlyAcceptedStreamDeterministicallyAtBound()
    {
        var correlator = new ResultCorrelator(
            new FakeRelayClock(),
            TimeSpan.FromSeconds(2),
            maximumPending: 2,
            maximumTrackedStreams: 2);
        Accept(correlator, TestFrames.Frame("one", streamId: "stream-one", frameId: 1));
        Accept(correlator, TestFrames.Frame("two", streamId: "stream-two", frameId: 1));
        Accept(correlator, TestFrames.Frame("three", streamId: "stream-three", frameId: 1));

        var evictedStreamFrame = TestFrames.Frame("one-retry", streamId: "stream-one", frameId: 1);
        correlator.Register(evictedStreamFrame);
        var evictedStreamDecision = correlator.Accept(TestFrames.Result(evictedStreamFrame));
        var retainedStreamFrame = TestFrames.Frame("three-retry", streamId: "stream-three", frameId: 1);
        correlator.Register(retainedStreamFrame);
        var retainedStreamDecision = correlator.Accept(TestFrames.Result(retainedStreamFrame));

        Assert.True(evictedStreamDecision.Accepted);
        Assert.Equal(CorrelationRejection.OutOfOrder, retainedStreamDecision.Rejection);
    }

    [Fact]
    public void Accept_DoesNotEvictTrackedStreamWithPendingFrame()
    {
        var correlator = new ResultCorrelator(
            new FakeRelayClock(),
            TimeSpan.FromSeconds(2),
            maximumPending: 2,
            maximumTrackedStreams: 2);
        Accept(correlator, TestFrames.Frame("one-new", streamId: "stream-one", frameId: 2));
        Accept(correlator, TestFrames.Frame("two", streamId: "stream-two", frameId: 1));
        var pendingOlderFrame = TestFrames.Frame("one-old", streamId: "stream-one", frameId: 1);
        correlator.Register(pendingOlderFrame);

        Accept(correlator, TestFrames.Frame("three", streamId: "stream-three", frameId: 1));
        var decision = correlator.Accept(TestFrames.Result(pendingOlderFrame));

        Assert.Equal(CorrelationRejection.OutOfOrder, decision.Rejection);
    }

    [Fact]
    public void Constructor_RejectsTrackedStreamCapacityBelowPendingCapacity()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            new ResultCorrelator(
                new FakeRelayClock(),
                TimeSpan.FromSeconds(2),
                maximumPending: 2,
                maximumTrackedStreams: 1));
    }

    private static void Accept(ResultCorrelator correlator, FramePayload frame)
    {
        correlator.Register(frame);
        Assert.True(correlator.Accept(TestFrames.Result(frame)).Accepted);
    }
}
