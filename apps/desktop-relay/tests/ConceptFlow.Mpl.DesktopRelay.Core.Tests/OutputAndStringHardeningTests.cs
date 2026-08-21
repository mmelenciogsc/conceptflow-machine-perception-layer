// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.DesktopRelay.Core;
using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core.Tests;

public sealed class OutputAndStringHardeningTests
{
    [Fact]
    public async Task MalformedEncodedInput_IsRejectedBeforeTransport()
    {
        var transport = new DelegateProcessTransport(
            (frame, _, _) => Task.FromResult(TestFrames.Result(frame)));
        await using var session = new RelaySession(new DelegateTransportFactory(_ => transport));
        await session.ConnectAsync("https://localhost:7443");
        var malformed = TestFrames.Capture().Content.ToArray();
        malformed[29] ^= 0x01;

        await Assert.ThrowsAsync<ArgumentException>(() =>
            session.SubmitAsync(TestFrames.Capture() with { Content = malformed }));

        Assert.Equal(0, transport.ProcessCount);
    }

    [Fact]
    public async Task NonControlCueWithoutLocalOutput_FailsClosed()
    {
        var outcome = await SubmitCueAsync(TestFrames.ResultWithCue, cueOutput: null);

        Assert.False(outcome.Accepted);
        Assert.Equal(RelayDeliveryStatus.Failed, outcome.Delivery);
        Assert.Null(outcome.Result);
    }

    [Fact]
    public async Task FailedOutput_IsNeverReportedDeliveredOrAccepted()
    {
        var statuses = new RecordingStatusSink();
        var adapter = new RecordingCueOutputAdapter(delivers: false);

        var outcome = await SubmitCueAsync(TestFrames.ResultWithCue, adapter, statuses);

        Assert.False(outcome.Accepted);
        Assert.Equal(RelayDeliveryStatus.Failed, outcome.Delivery);
        Assert.DoesNotContain(statuses.Statuses, status => status.Code == "result_accepted");
        Assert.Contains(statuses.Statuses, status => status.Code == "cue_output_failed");
    }

    [Fact]
    public async Task OutputDeliveryTimeout_IsBoundedAndNotReportedDelivered()
    {
        var adapter = new HangingCueOutputAdapter();

        var outcome = await SubmitCueAsync(
            TestFrames.ResultWithCue,
            adapter,
            options: new RelayOptions { OutputTimeout = TimeSpan.FromMilliseconds(20) });

        Assert.False(outcome.Accepted);
        Assert.Equal(RelayDeliveryStatus.Failed, outcome.Delivery);
        adapter.Complete();
    }

    [Fact]
    public async Task SuccessfulSpeechOutput_IsAcceptedOnlyAfterDelivery()
    {
        var adapter = new RecordingCueOutputAdapter();

        var outcome = await SubmitCueAsync(TestFrames.ResultWithCue, adapter);

        Assert.True(outcome.Accepted);
        Assert.Equal(RelayDeliveryStatus.Delivered, outcome.Delivery);
        Assert.Single(adapter.Delivered);
    }

    [Fact]
    public async Task UnnegotiatedEarcon_IsRejectedEvenWhenSpeechIsPresent()
    {
        var adapter = new RecordingCueOutputAdapter();

        var outcome = await SubmitCueAsync(
            frame =>
            {
                var result = TestFrames.ResultWithCue(frame);
                result.Cues[0].Earcon = new Earcon { EarconId = "warning" };
                return result;
            },
            adapter);

        Assert.False(outcome.Accepted);
        Assert.Empty(adapter.Delivered);
    }

    [Fact]
    public async Task ExplicitControlOnlyCancellation_IsAppliedWithoutClaimingContentDelivery()
    {
        var adapter = new RecordingCueOutputAdapter();

        var outcome = await SubmitCueAsync(
            frame =>
            {
                var result = TestFrames.ResultWithCue(frame);
                var cue = result.Cues[0];
                cue.Category = CueCategory.System;
                cue.Description = string.Empty;
                cue.Speech = null;
                cue.Cancel = new CueCancellation { CueIds = { "cue-prior" }, Reason = "stale" };
                return result;
            },
            adapter);

        Assert.True(outcome.Accepted);
        Assert.Equal(RelayDeliveryStatus.ControlApplied, outcome.Delivery);
        Assert.Equal("Control cue applied.", outcome.Text);
    }

    [Fact]
    public async Task ControlCueWithOutputPayload_IsRejectedAsAmbiguous()
    {
        var adapter = new RecordingCueOutputAdapter();

        var outcome = await SubmitCueAsync(
            frame =>
            {
                var result = TestFrames.ResultWithCue(frame);
                result.Cues[0].Cancel = new CueCancellation { CueIds = { "cue-prior" } };
                return result;
            },
            adapter);

        Assert.False(outcome.Accepted);
        Assert.Empty(adapter.Delivered);
    }

    [Theory]
    [InlineData("description_max")]
    [InlineData("speech_control")]
    [InlineData("speech_bidi")]
    [InlineData("speech_utf8")]
    [InlineData("speech_surrogate")]
    [InlineData("cue_email_id")]
    [InlineData("result_path_id")]
    [InlineData("provenance_control")]
    [InlineData("error_max")]
    public async Task ResultAndCueStrings_AreTightlyBoundedAndDisplaySafe(string defect)
    {
        var adapter = new RecordingCueOutputAdapter();

        var outcome = await SubmitCueAsync(
            frame =>
            {
                var result = TestFrames.ResultWithCue(frame);
                switch (defect)
                {
                    case "description_max":
                        result.Cues[0].Description = new string('d', 257);
                        break;
                    case "speech_control":
                        result.Cues[0].Speech.Text = "attention\nforged";
                        break;
                    case "speech_bidi":
                        result.Cues[0].Speech.Text = "attention\u202Eforged";
                        break;
                    case "speech_utf8":
                        result.Cues[0].Speech.Text = string.Concat(Enumerable.Repeat("😀", 200));
                        break;
                    case "speech_surrogate":
                        result.Cues[0].Speech.Text = "attention\uD800forged";
                        break;
                    case "cue_email_id":
                        result.Cues[0].CueId = "person@example.com";
                        break;
                    case "result_path_id":
                        result.ResultId = "C:\\private\\result";
                        break;
                    case "provenance_control":
                        result.Provenance = new Provenance { Component = "worker\nforged" };
                        break;
                    case "error_max":
                        result.Error = new ErrorStatus
                        {
                            Code = ErrorCode.Internal,
                            Message = new string('e', 257),
                        };
                        break;
                }

                return result;
            },
            adapter);

        Assert.False(outcome.Accepted);
        Assert.Equal(RelayDeliveryStatus.Failed, outcome.Delivery);
        Assert.Empty(adapter.Delivered);
    }

    [Fact]
    public async Task RelayOutcomeText_IsBoundedForLargestLegalDescription()
    {
        var adapter = new RecordingCueOutputAdapter();

        var outcome = await SubmitCueAsync(
            frame =>
            {
                var result = TestFrames.ResultWithCue(frame);
                result.Cues[0].Description = new string('d', 256);
                return result;
            },
            adapter);

        Assert.True(outcome.Accepted);
        Assert.InRange(outcome.Text.Length, 1, 512);
    }

    [Theory]
    [InlineData(false, Urgency.Normal, CueAttention.Polite)]
    [InlineData(true, Urgency.Normal, CueAttention.Assertive)]
    [InlineData(false, Urgency.High, CueAttention.Assertive)]
    [InlineData(false, Urgency.Critical, CueAttention.Assertive)]
    public void ScreenReaderAttention_TracksProtocolInterruptAndUrgency(
        bool interrupt,
        Urgency urgency,
        CueAttention expected)
    {
        var cue = new PerceptionCue
        {
            Speech = new Speech { Text = "attention", Interrupt = interrupt },
            Urgency = urgency,
        };

        Assert.Equal(expected, CueAttentionPolicy.For(cue));
    }

    [Fact]
    public async Task ApprovedCaptureStatus_DoesNotExposeSourceCategory()
    {
        var statuses = new RecordingStatusSink();
        var transport = new DelegateProcessTransport(
            (frame, _, _) => Task.FromResult(TestFrames.Result(frame)));
        await using var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            statusSink: statuses);
        await session.ConnectAsync("https://localhost:7443");

        await session.SubmitAsync(TestFrames.Capture() with { Source = "user-selected-window" });

        Assert.DoesNotContain(
            statuses.Statuses,
            status => status.Message.Contains("user-selected-window", StringComparison.Ordinal));
    }

    [Fact]
    public async Task ServiceError_DoesNotExposeRemoteEmailPathOrHostname()
    {
        const string privateDetail = "person@example.com failed at C:\\private\\model.bin on relay.internal";
        var statuses = new RecordingStatusSink();
        var transport = new DelegateProcessTransport(
            (frame, _, _) =>
            {
                var result = TestFrames.Result(frame);
                result.Error = new ErrorStatus { Code = ErrorCode.Internal, Message = privateDetail };
                return Task.FromResult(result);
            });
        await using var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            statusSink: statuses);
        await session.ConnectAsync("https://localhost:7443");

        var outcome = await session.SubmitAsync(TestFrames.Capture());

        Assert.False(outcome.Accepted);
        Assert.Null(outcome.Result);
        Assert.DoesNotContain("person@example.com", outcome.Text, StringComparison.Ordinal);
        Assert.DoesNotContain("model.bin", outcome.Text, StringComparison.Ordinal);
        Assert.DoesNotContain("relay.internal", outcome.Text, StringComparison.Ordinal);
        Assert.DoesNotContain(
            statuses.Statuses,
            status => status.Message.Contains("person@example.com", StringComparison.Ordinal) ||
                status.Message.Contains("model.bin", StringComparison.Ordinal) ||
                status.Message.Contains("relay.internal", StringComparison.Ordinal));
    }

    [Fact]
    public async Task ConnectionFailureStatus_DoesNotExposeRawExceptionDetails()
    {
        var statuses = new RecordingStatusSink();
        var options = new RelayOptions { MaximumReconnectAttempts = 1 };
        await using var session = new RelaySession(
            new DelegateTransportFactory(_ => new FailingConnectTransport()),
            options,
            statusSink: statuses);

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            session.ConnectAsync("https://localhost:7443"));

        Assert.DoesNotContain(
            statuses.Statuses,
            status => status.Message.Contains("must-not-leak", StringComparison.Ordinal));
    }

    private static async Task<RelayOutcome> SubmitCueAsync(
        Func<FramePayload, PerceptionResult> createResult,
        IRelayCueOutputAdapter? cueOutput,
        IRelayStatusSink? statusSink = null,
        RelayOptions? options = null)
    {
        var clock = new FakeRelayClock();
        clock.Advance(TimeSpan.FromMilliseconds(1));
        var transport = new DelegateProcessTransport(
            (frame, _, _) => Task.FromResult(createResult(frame)));
        await using var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            options,
            clock: clock,
            statusSink: statusSink,
            cueOutput: cueOutput);
        await session.ConnectAsync("https://localhost:7443");
        return await session.SubmitAsync(TestFrames.Capture());
    }
}
