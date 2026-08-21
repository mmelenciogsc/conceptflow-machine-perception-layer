// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.DesktopRelay.Core;
using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core.Tests;

public sealed class RelaySessionTests
{
    [Fact]
    public async Task SubmitAsync_DoesNotExposePayloadFromMismatchedResult()
    {
        const string untrustedDescription = "untrusted mismatched description must-not-leak";
        var statuses = new List<SafeStatus>();
        var transport = new DelegateProcessTransport(
            (frame, _, _) =>
            {
                var result = TestFrames.ResultWithCue(frame);
                result.SessionId = "wrong-session";
                result.Cues[0].Description = untrustedDescription;
                return Task.FromResult(result);
            });
        await using var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            statusSink: new DelegateStatusSink(statuses.Add));
        await session.ConnectAsync("https://localhost:7443");

        var outcome = await session.SubmitAsync(TestFrames.Capture());

        Assert.False(outcome.Accepted);
        Assert.Equal(CorrelationRejection.SessionMismatch, outcome.Correlation.Rejection);
        Assert.Empty(outcome.ResultId);
        Assert.Null(outcome.Result);
        Assert.DoesNotContain(untrustedDescription, outcome.Text, StringComparison.Ordinal);
        Assert.DoesNotContain(statuses, status => status.Message.Contains(untrustedDescription, StringComparison.Ordinal));
    }

    [Fact]
    public async Task NegotiatedPolicy_EnforcesEncodingDimensionsAndBytesBeforeTransport()
    {
        var validPng = TestFrames.Capture();
        var maximumFrameBytes = validPng.Content.Length;
        var transport = new DelegateProcessTransport(
            (frame, _, _) => Task.FromResult(TestFrames.Result(frame)),
            sessionId => TestNegotiations.Create(
                sessionId,
                maximumWidth: 2,
                maximumHeight: 3,
                maximumFrameBytes: maximumFrameBytes,
                maximumInFlight: 1,
                imageEncodings: [ImageEncoding.Png]));
        var options = new RelayOptions
        {
            MaximumWidth = 20,
            MaximumHeight = 30,
            MaximumFrameBytes = 1024,
            MaximumMessageBytes = 2048,
            QueueCapacity = 4,
        };
        await using var session = new RelaySession(new DelegateTransportFactory(_ => transport), options);
        await session.ConnectAsync("https://localhost:7443");

        var policy = Assert.IsType<NegotiatedRelayPolicy>(session.NegotiatedPolicy);
        Assert.Equal(2, policy.MaximumWidth);
        Assert.Equal(3, policy.MaximumHeight);
        Assert.Equal(maximumFrameBytes, policy.MaximumFrameBytes);
        Assert.Equal(1, policy.MaximumInFlight);
        Assert.Equal([ImageEncoding.Png], policy.SupportedImageEncodings);

        await Assert.ThrowsAsync<NotSupportedException>(() =>
            session.SubmitAsync(TestFrames.Capture(encoding: ImageEncoding.Jpeg)));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() =>
            session.SubmitAsync(TestFrames.PngCapture(3, 1)));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() =>
            session.SubmitAsync(TestFrames.PngCapture(1, 4)));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() =>
            session.SubmitAsync(TestFrames.PngCaptureWithAncillaryChunks()));
        Assert.Equal(0, transport.ProcessCount);

        var valid = await session.SubmitAsync(
            TestFrames.PngCapture(2, 3));

        Assert.True(valid.Accepted);
        Assert.Equal(1, transport.ProcessCount);
    }

    [Fact]
    public async Task NegotiatedPolicy_UsesStricterLocalLimits()
    {
        var transport = new DelegateProcessTransport(
            (frame, _, _) => Task.FromResult(TestFrames.Result(frame)),
            sessionId => TestNegotiations.Create(
                sessionId,
                maximumWidth: 200,
                maximumHeight: 300,
                maximumFrameBytes: 400,
                maximumInFlight: 8,
                imageEncodings: [ImageEncoding.Jpeg, ImageEncoding.Png]));
        var options = new RelayOptions
        {
            MaximumWidth = 20,
            MaximumHeight = 30,
            MaximumFrameBytes = 40,
            MaximumMessageBytes = 128,
            QueueCapacity = 2,
        };
        await using var session = new RelaySession(new DelegateTransportFactory(_ => transport), options);

        await session.ConnectAsync("https://localhost:7443");

        var policy = Assert.IsType<NegotiatedRelayPolicy>(session.NegotiatedPolicy);
        Assert.Equal(20, policy.MaximumWidth);
        Assert.Equal(30, policy.MaximumHeight);
        Assert.Equal(40, policy.MaximumFrameBytes);
        Assert.Equal(2, policy.MaximumInFlight);
    }

    [Theory]
    [InlineData("width")]
    [InlineData("height")]
    [InlineData("bytes")]
    [InlineData("in_flight")]
    [InlineData("encodings")]
    public async Task ConnectAsync_RejectsUnusableNegotiatedLimits(string malformedField)
    {
        var transport = new DelegateProcessTransport(
            (frame, _, _) => Task.FromResult(TestFrames.Result(frame)),
            sessionId =>
            {
                var negotiation = TestNegotiations.Create(sessionId);
                switch (malformedField)
                {
                    case "width":
                        negotiation.Capabilities.MaxWidth = 0;
                        break;
                    case "height":
                        negotiation.Capabilities.MaxHeight = 0;
                        break;
                    case "bytes":
                        negotiation.Capabilities.MaxFrameBytes = 0;
                        break;
                    case "in_flight":
                        negotiation.QualityOfService.MaxInFlight = 0;
                        break;
                    case "encodings":
                        negotiation.Capabilities.ImageEncodings.Clear();
                        break;
                }

                return negotiation;
            });
        var options = new RelayOptions { MaximumReconnectAttempts = 1 };
        await using var session = new RelaySession(new DelegateTransportFactory(_ => transport), options);

        var exception = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            session.ConnectAsync("https://localhost:7443"));

        Assert.Contains("exhausted", exception.Message, StringComparison.OrdinalIgnoreCase);
        Assert.Null(session.NegotiatedPolicy);
        Assert.Equal(RelaySessionState.Failed, session.Snapshot.State);
    }

    [Fact]
    public async Task ConcurrentSubmissions_ApplyNegotiatedBackpressureAndProcessAcceptedExactlyOnce()
    {
        var firstStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var bothStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var firstRelease = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var secondRelease = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var transport = new DelegateProcessTransport(
            async (frame, callNumber, cancellationToken) =>
            {
                if (callNumber == 1)
                {
                    firstStarted.TrySetResult();
                    await firstRelease.Task.WaitAsync(cancellationToken);
                }
                else
                {
                    bothStarted.TrySetResult();
                    await secondRelease.Task.WaitAsync(cancellationToken);
                }

                return TestFrames.Result(frame);
            },
            sessionId => TestNegotiations.Create(sessionId, maximumInFlight: 2));
        await using var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            new RelayOptions { QueueCapacity = 4 });
        await session.ConnectAsync("https://localhost:7443");

        var first = session.SubmitAsync(TestFrames.Capture() with { RequestId = "request-1", FrameId = 1 });
        await firstStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));
        var second = session.SubmitAsync(TestFrames.Capture() with { RequestId = "request-2", FrameId = 2 });
        await bothStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));

        var overflow = await session.SubmitAsync(
            TestFrames.Capture() with { RequestId = "request-overflow", FrameId = 3 });

        Assert.False(overflow.Accepted);
        Assert.Contains("capacity", overflow.Text, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(2, transport.ProcessCount);
        Assert.Equal(2, transport.MaxConcurrentCount);
        Assert.Equal(0, transport.RequestCount("request-overflow"));

        firstRelease.TrySetResult();
        Assert.True((await first).Accepted);
        secondRelease.TrySetResult();
        Assert.True((await second).Accepted);
        Assert.Equal(1, transport.RequestCount("request-1"));
        Assert.Equal(1, transport.RequestCount("request-2"));
    }

    [Fact]
    public async Task CallerCancellation_ReleasesAdmissionCapacity()
    {
        var firstStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var transport = new DelegateProcessTransport(
            async (frame, callNumber, cancellationToken) =>
            {
                if (callNumber == 1)
                {
                    firstStarted.TrySetResult();
                    await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                }

                return TestFrames.Result(frame);
            },
            sessionId => TestNegotiations.Create(sessionId, maximumInFlight: 1));
        await using var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            new RelayOptions { QueueCapacity = 1, RequestTimeout = TimeSpan.FromSeconds(5) });
        await session.ConnectAsync("https://localhost:7443");
        using var cancellation = new CancellationTokenSource();

        var cancelled = session.SubmitAsync(
            TestFrames.Capture() with { RequestId = "request-cancelled", FrameId = 1 },
            cancellation.Token);
        await firstStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => cancelled);
        Assert.True(SpinWait.SpinUntil(() => transport.ActiveCount == 0, TimeSpan.FromSeconds(2)));

        var replacement = await SubmitWhenCapacityIsReleasedAsync(
            session,
            TestFrames.Capture() with { RequestId = "request-replacement", FrameId = 2 });

        Assert.True(replacement.Accepted);
        Assert.Equal(2, transport.ProcessCount);
        Assert.Equal(1, transport.RequestCount("request-cancelled"));
        Assert.Equal(1, transport.RequestCount("request-replacement"));
    }

    [Fact]
    public async Task RequestTimeout_CancelsCorrelationAndReleasesAdmissionCapacity()
    {
        var firstStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var cancellationObserved = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var transport = new DelegateProcessTransport(
            async (frame, callNumber, cancellationToken) =>
            {
                if (callNumber == 1)
                {
                    firstStarted.TrySetResult();
                    try
                    {
                        await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                    }
                    catch (OperationCanceledException)
                    {
                        cancellationObserved.TrySetResult();
                        throw;
                    }
                }

                return TestFrames.Result(frame);
            },
            sessionId => TestNegotiations.Create(sessionId, maximumInFlight: 1));
        await using var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            new RelayOptions { QueueCapacity = 1, RequestTimeout = TimeSpan.FromMilliseconds(50) });
        await session.ConnectAsync("https://localhost:7443");

        var timedOut = session.SubmitAsync(
            TestFrames.Capture() with { RequestId = "request-timeout", FrameId = 1 });
        await firstStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));
        await Assert.ThrowsAsync<TimeoutException>(() => timedOut);
        await cancellationObserved.Task.WaitAsync(TimeSpan.FromSeconds(2));
        Assert.True(SpinWait.SpinUntil(() => transport.ActiveCount == 0, TimeSpan.FromSeconds(2)));

        var replacement = await SubmitWhenCapacityIsReleasedAsync(
            session,
            TestFrames.Capture() with { RequestId = "request-after-timeout", FrameId = 2 });

        Assert.True(replacement.Accepted);
        Assert.Equal(2, transport.ProcessCount);
        Assert.Equal(1, transport.RequestCount("request-timeout"));
        Assert.Equal(1, transport.RequestCount("request-after-timeout"));
    }

    [Fact]
    public async Task ConnectAsync_TransitionsThroughBackoffAndReconnects()
    {
        var factory = new DelegateTransportFactory(attempt =>
            attempt < 3 ? new FailingConnectTransport() : new InProcessRelayTransport());
        var options = new RelayOptions
        {
            MaximumReconnectAttempts = 3,
            InitialReconnectDelay = TimeSpan.Zero,
            MaximumReconnectDelay = TimeSpan.Zero,
        };
        await using var session = new RelaySession(factory, options);
        var states = new List<RelaySessionState>();
        session.SnapshotChanged += snapshot => states.Add(snapshot.State);

        var snapshot = await session.ConnectAsync("https://localhost:7443");

        Assert.Equal(RelaySessionState.Active, snapshot.State);
        Assert.Equal(3, snapshot.Attempt);
        Assert.Equal(3, factory.CreateCount);
        Assert.Contains(RelaySessionState.Connecting, states);
        Assert.Contains(RelaySessionState.Negotiating, states);
        Assert.Equal(2, states.Count(state => state == RelaySessionState.ReconnectBackoff));
    }

    [Fact]
    public async Task SubmitAsync_PropagatesTimeoutAndCancelsTransport()
    {
        var transport = new HangingProcessTransport();
        var options = new RelayOptions { RequestTimeout = TimeSpan.FromMilliseconds(25) };
        await using var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            options);
        await session.ConnectAsync("https://localhost:7443");

        await Assert.ThrowsAsync<TimeoutException>(() => session.SubmitAsync(TestFrames.Capture()));
        await transport.CancellationObservedTask.WaitAsync(TimeSpan.FromSeconds(2));
        Assert.True(transport.CancellationObserved);
    }

    [Fact]
    public async Task SubmitAsync_HonorsCallerCancellation()
    {
        var transport = new HangingProcessTransport();
        await using var session = new RelaySession(new DelegateTransportFactory(_ => transport));
        await session.ConnectAsync("https://localhost:7443");
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(25));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => session.SubmitAsync(TestFrames.Capture(), cancellation.Token));
        await transport.CancellationObservedTask.WaitAsync(TimeSpan.FromSeconds(2));
        Assert.True(transport.CancellationObserved);
    }

    [Fact]
    public async Task SubmitAsync_HasHardDeadlineWhenTransportIgnoresCancellation()
    {
        var transport = new CancellationIgnoringTransport();
        var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            new RelayOptions
            {
                QueueCapacity = 1,
                RequestTimeout = TimeSpan.FromMilliseconds(25),
                ShutdownTimeout = TimeSpan.FromMilliseconds(25),
            });
        await session.ConnectAsync("https://localhost:7443");

        var submission = session.SubmitAsync(TestFrames.Capture());
        await transport.Started.Task.WaitAsync(TimeSpan.FromSeconds(2));

        await Assert.ThrowsAsync<TimeoutException>(() => submission.WaitAsync(TimeSpan.FromSeconds(1)));
        var backpressured = await session.SubmitAsync(
            TestFrames.Capture() with { RequestId = "request-while-abandoned", FrameId = 2 });
        Assert.False(backpressured.Accepted);
        Assert.Contains("capacity", backpressured.Text, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(0, transport.DisposeCount);

        await session.StopAsync().WaitAsync(TimeSpan.FromSeconds(1));
        transport.Fail();
        await transport.Disposed.Task.WaitAsync(TimeSpan.FromSeconds(2));
        await session.DisposeAsync();
    }

    [Fact]
    public async Task InProcessVerticalSlice_ReturnsInspectableSyntheticCue()
    {
        await using var session = new RelaySession(
            new InProcessRelayTransportFactory(),
            cueOutput: new RecordingCueOutputAdapter());
        await session.ConnectAsync("https://localhost:7443");

        var outcome = await session.SubmitAsync(TestFrames.Capture());

        Assert.True(outcome.Accepted);
        Assert.True(outcome.Correlation.Accepted);
        Assert.Contains("Synthetic demo frame", outcome.Text, StringComparison.Ordinal);
        Assert.True(outcome.Result!.Provenance.Synthetic);
    }

    [Fact]
    public async Task SubmitAsync_RejectsMissingConsentBeforeTransport()
    {
        await using var session = new RelaySession(new InProcessRelayTransportFactory());
        await session.ConnectAsync("https://localhost:7443");

        var error = await Assert.ThrowsAsync<InvalidOperationException>(
            () => session.SubmitAsync(TestFrames.Capture(consent: false)));

        Assert.Contains("consent", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task StopAsync_ClearsSessionAndCloses()
    {
        await using var session = new RelaySession(new InProcessRelayTransportFactory());
        await session.ConnectAsync("https://localhost:7443");

        await session.StopAsync();

        Assert.Equal(RelaySessionState.Closed, session.Snapshot.State);
        Assert.Null(session.Snapshot.SessionId);
        Assert.Null(session.NegotiatedPolicy);
    }

    [Fact]
    public async Task StopAsync_CancelsConcurrentInFlightSubmissionsAndDrainsCapacity()
    {
        var bothStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var startedCount = 0;
        var transport = new DelegateProcessTransport(
            async (_, _, cancellationToken) =>
            {
                if (Interlocked.Increment(ref startedCount) == 2)
                {
                    bothStarted.TrySetResult();
                }

                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                throw new InvalidOperationException("The cancelled transport unexpectedly completed.");
            },
            sessionId => TestNegotiations.Create(sessionId, maximumInFlight: 2));
        var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            new RelayOptions { QueueCapacity = 2, RequestTimeout = TimeSpan.FromSeconds(5) });
        await session.ConnectAsync("https://localhost:7443");
        var first = session.SubmitAsync(TestFrames.Capture() with { RequestId = "stop-1", FrameId = 1 });
        var second = session.SubmitAsync(TestFrames.Capture() with { RequestId = "stop-2", FrameId = 2 });
        await bothStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));

        await session.StopAsync();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => first);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => second);
        Assert.Equal(RelaySessionState.Closed, session.Snapshot.State);
        Assert.Null(session.NegotiatedPolicy);
        await session.DisposeAsync();
    }

    [Fact]
    public async Task StopAsync_IsBoundedAndDefersDisposalForCancellationIgnoringOperation()
    {
        var transport = new CancellationIgnoringTransport();
        var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            new RelayOptions
            {
                RequestTimeout = TimeSpan.FromSeconds(5),
                ShutdownTimeout = TimeSpan.FromMilliseconds(25),
            });
        await session.ConnectAsync("https://localhost:7443");
        var submission = session.SubmitAsync(TestFrames.Capture());
        await transport.Started.Task.WaitAsync(TimeSpan.FromSeconds(2));

        await session.StopAsync().WaitAsync(TimeSpan.FromSeconds(1));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => submission);
        Assert.Equal(0, transport.DisposeCount);
        Assert.Equal(RelaySessionState.Closed, session.Snapshot.State);
        Assert.Equal(1, session.RetainedCleanupTaskCount);

        transport.Fail();
        await transport.Disposed.Task.WaitAsync(TimeSpan.FromSeconds(2));
        Assert.Equal(1, transport.DisposeCount);
        Assert.True(SpinWait.SpinUntil(() => session.RetainedCleanupTaskCount == 0, TimeSpan.FromSeconds(2)));
        await session.DisposeAsync();
    }

    [Fact]
    public async Task DisposeAsync_IsBoundedAndDefersDisposalForCancellationIgnoringOperation()
    {
        var transport = new CancellationIgnoringTransport();
        var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            new RelayOptions
            {
                RequestTimeout = TimeSpan.FromSeconds(5),
                ShutdownTimeout = TimeSpan.FromMilliseconds(25),
            });
        await session.ConnectAsync("https://localhost:7443");
        var submission = session.SubmitAsync(TestFrames.Capture());
        await transport.Started.Task.WaitAsync(TimeSpan.FromSeconds(2));

        await session.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(1));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => submission);
        Assert.Equal(0, transport.DisposeCount);
        Assert.Equal(1, session.RetainedCleanupTaskCount);

        transport.Complete();
        await transport.Disposed.Task.WaitAsync(TimeSpan.FromSeconds(2));
        Assert.Equal(1, transport.DisposeCount);
        Assert.True(SpinWait.SpinUntil(() => session.RetainedCleanupTaskCount == 0, TimeSpan.FromSeconds(2)));
    }

    [Fact]
    public async Task StopAsync_IsBoundedWhenTransportDisposalDoesNotComplete()
    {
        var transport = new CancellationIgnoringDisposeTransport();
        var session = new RelaySession(
            new DelegateTransportFactory(_ => transport),
            new RelayOptions { ShutdownTimeout = TimeSpan.FromMilliseconds(25) });
        await session.ConnectAsync("https://localhost:7443");

        var stop = session.StopAsync();
        await transport.DisposeStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));

        await stop.WaitAsync(TimeSpan.FromSeconds(1));
        Assert.Equal(RelaySessionState.Closed, session.Snapshot.State);

        transport.CompleteDisposal();
        await session.DisposeAsync();
    }

    [Fact]
    public async Task ReconnectsStopAtCleanupQuarantineCapacity()
    {
        var transports = new[] { new CancellationIgnoringTransport(), new CancellationIgnoringTransport() };
        var factory = new DelegateTransportFactory(attempt => transports[attempt - 1]);
        var session = new RelaySession(
            factory,
            new RelayOptions
            {
                MaximumReconnectAttempts = 1,
                MaximumRetainedCleanupTasks = 2,
                RequestTimeout = TimeSpan.FromSeconds(5),
                ShutdownTimeout = TimeSpan.FromMilliseconds(25),
            });

        for (var index = 0; index < transports.Length; index++)
        {
            await session.ConnectAsync("https://localhost:7443");
            var submission = session.SubmitAsync(
                TestFrames.Capture() with { RequestId = $"quarantine-{index}", FrameId = (ulong)index + 1 });
            await transports[index].Started.Task.WaitAsync(TimeSpan.FromSeconds(2));
            await session.StopAsync().WaitAsync(TimeSpan.FromSeconds(1));
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => submission);
        }

        Assert.Equal(2, session.RetainedCleanupTaskCount);
        var error = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            session.ConnectAsync("https://localhost:7443"));
        Assert.Contains("exhausted", error.Message, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(2, factory.CreateCount);

        foreach (var transport in transports)
        {
            transport.Complete();
            await transport.Disposed.Task.WaitAsync(TimeSpan.FromSeconds(2));
        }
        Assert.True(SpinWait.SpinUntil(() => session.RetainedCleanupTaskCount == 0, TimeSpan.FromSeconds(2)));
        await session.DisposeAsync();
    }

    [Fact]
    public async Task FailedReconnect_ClearsNegotiatedPolicy()
    {
        var initialTransport = new DelegateProcessTransport(
            (frame, _, _) => Task.FromResult(TestFrames.Result(frame)));
        var factory = new DelegateTransportFactory(attempt =>
            attempt == 1 ? initialTransport : new FailingConnectTransport());
        var options = new RelayOptions { MaximumReconnectAttempts = 1 };
        await using var session = new RelaySession(factory, options);
        await session.ConnectAsync("https://localhost:7443");
        Assert.NotNull(session.NegotiatedPolicy);

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            session.ConnectAsync("https://localhost:7443"));

        Assert.Null(session.NegotiatedPolicy);
        Assert.Equal(RelaySessionState.Failed, session.Snapshot.State);
    }

    private static async Task<RelayOutcome> SubmitWhenCapacityIsReleasedAsync(
        RelaySession session,
        CaptureSubmission submission)
    {
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        RelayOutcome outcome;
        do
        {
            outcome = await session.SubmitAsync(submission, deadline.Token);
            if (outcome.Accepted || !outcome.Text.Contains("capacity", StringComparison.OrdinalIgnoreCase))
            {
                return outcome;
            }

            await Task.Delay(1, deadline.Token);
        }
        while (!deadline.IsCancellationRequested);

        return outcome;
    }

    private sealed class DelegateStatusSink(Action<SafeStatus> report) : IRelayStatusSink
    {
        public void Report(SafeStatus status) => report(status);
    }
}
