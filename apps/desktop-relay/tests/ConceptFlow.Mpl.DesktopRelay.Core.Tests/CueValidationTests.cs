// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.DesktopRelay.Core;
using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core.Tests;

public sealed class CueValidationTests
{
    [Fact]
    public async Task SubmitAsync_AcceptsCueWithCorrelatedIdentityAndLiveTtl()
    {
        var clock = StartedClock();

        var outcome = await SubmitResultAsync(clock, TestFrames.ResultWithCue);

        Assert.True(outcome.Accepted);
        Assert.Equal("Validated synthetic cue.", outcome.Text);
        Assert.NotNull(outcome.Result);
    }

    [Fact]
    public async Task SubmitAsync_RejectsCueWhoseFrameDoesNotMatchCorrelatedResult()
    {
        var clock = StartedClock();

        var outcome = await SubmitResultAsync(
            clock,
            frame =>
            {
                var result = TestFrames.ResultWithCue(frame);
                result.Cues[0].FrameId++;
                return result;
            });

        Assert.False(outcome.Accepted);
        Assert.Equal(CorrelationRejection.FrameMismatch, outcome.Correlation.Rejection);
        Assert.Null(outcome.Result);
        Assert.DoesNotContain("Validated synthetic cue", outcome.Text, StringComparison.Ordinal);
    }

    [Fact]
    public async Task SubmitAsync_ValidatesEveryCueBeforeSurfacingTheFirst()
    {
        var clock = StartedClock();

        var outcome = await SubmitResultAsync(
            clock,
            frame =>
            {
                var result = TestFrames.ResultWithCue(frame);
                var invalid = TestFrames.Cue(frame);
                invalid.CueId = "cue-invalid-frame";
                invalid.FrameId++;
                result.Cues.Add(invalid);
                return result;
            });

        Assert.False(outcome.Accepted);
        Assert.Equal(CorrelationRejection.FrameMismatch, outcome.Correlation.Rejection);
        Assert.Null(outcome.Result);
        Assert.DoesNotContain("Validated synthetic cue", outcome.Text, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("missing_id")]
    [InlineData("zero_frame")]
    [InlineData("duplicate_id")]
    [InlineData("zero_timestamp")]
    [InlineData("timestamp_before_capture")]
    [InlineData("timestamp_after_completion")]
    [InlineData("timestamp_in_future")]
    [InlineData("completion_before_capture")]
    [InlineData("completion_in_future")]
    [InlineData("zero_ttl")]
    public async Task SubmitAsync_RejectsMalformedCueIdentityAndTime(string defect)
    {
        var clock = StartedClock();

        var outcome = await SubmitResultAsync(
            clock,
            frame =>
            {
                var result = TestFrames.ResultWithCue(frame);
                var cue = result.Cues[0];
                switch (defect)
                {
                    case "missing_id":
                        cue.CueId = " ";
                        break;
                    case "zero_frame":
                        cue.FrameId = 0;
                        break;
                    case "duplicate_id":
                        result.Cues.Add(TestFrames.Cue(frame));
                        break;
                    case "zero_timestamp":
                        cue.CreatedMonotonicTimestampNs = 0;
                        break;
                    case "timestamp_before_capture":
                        cue.CreatedMonotonicTimestampNs--;
                        break;
                    case "timestamp_after_completion":
                        cue.CreatedMonotonicTimestampNs++;
                        break;
                    case "timestamp_in_future":
                        cue.CreatedMonotonicTimestampNs++;
                        result.CompletedMonotonicTimestampNs = cue.CreatedMonotonicTimestampNs;
                        break;
                    case "completion_before_capture":
                        result.CompletedMonotonicTimestampNs--;
                        break;
                    case "completion_in_future":
                        result.CompletedMonotonicTimestampNs++;
                        break;
                    case "zero_ttl":
                        cue.TtlMs = 0;
                        break;
                }

                return result;
            });

        Assert.False(outcome.Accepted);
        Assert.Equal(CorrelationRejection.InvalidCue, outcome.Correlation.Rejection);
        Assert.Null(outcome.Result);
    }

    [Theory]
    [InlineData(double.NaN)]
    [InlineData(double.PositiveInfinity)]
    [InlineData(double.NegativeInfinity)]
    [InlineData(-0.01)]
    [InlineData(1.01)]
    public async Task SubmitAsync_RejectsNonFiniteOrOutOfRangeConfidence(double confidence)
    {
        var clock = StartedClock();

        var outcome = await SubmitResultAsync(
            clock,
            frame =>
            {
                var result = TestFrames.ResultWithCue(frame);
                result.Cues[0].Confidence = confidence;
                return result;
            });

        Assert.False(outcome.Accepted);
        Assert.Equal(CorrelationRejection.InvalidCue, outcome.Correlation.Rejection);
        Assert.Null(outcome.Result);
    }

    [Fact]
    public async Task SubmitAsync_RejectsCueAtExactTtlExpiry()
    {
        var clock = StartedClock();

        var outcome = await SubmitResultAsync(
            clock,
            frame =>
            {
                var result = TestFrames.ResultWithCue(frame);
                result.Cues[0].TtlMs = 10;
                clock.Advance(TimeSpan.FromMilliseconds(10));
                return result;
            });

        Assert.False(outcome.Accepted);
        Assert.Equal(CorrelationRejection.Stale, outcome.Correlation.Rejection);
        Assert.Null(outcome.Result);
    }

    private static FakeRelayClock StartedClock()
    {
        var clock = new FakeRelayClock();
        clock.Advance(TimeSpan.FromMilliseconds(1));
        return clock;
    }

    private static async Task<RelayOutcome> SubmitResultAsync(
        FakeRelayClock clock,
        Func<FramePayload, PerceptionResult> createResult)
    {
        var transport = new DelegateProcessTransport(
            (frame, _, _) => Task.FromResult(createResult(frame)));
        await using var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            clock: clock,
            cueOutput: new RecordingCueOutputAdapter());
        await session.ConnectAsync("https://localhost:7443");
        return await session.SubmitAsync(TestFrames.Capture());
    }
}
