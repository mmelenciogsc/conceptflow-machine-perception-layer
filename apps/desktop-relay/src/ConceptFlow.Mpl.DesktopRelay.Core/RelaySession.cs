// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Collections.Concurrent;
using System.Runtime.ExceptionServices;
using System.Security.Cryptography;
using ConceptFlow.Mpl.V1;
using Google.Protobuf;
using Google.Protobuf.WellKnownTypes;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public sealed class RelaySession : IAsyncDisposable
{
    private sealed class PendingSubmission
    {
        public PendingSubmission(FramePayload frame, CancellationToken callerCancellation)
        {
            Frame = frame;
            CallerCancellation = callerCancellation;
        }

        public FramePayload Frame { get; }

        public CancellationToken CallerCancellation { get; }

        public TaskCompletionSource<RelayOutcome> Completion { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public CancellationTokenRegistration CancellationRegistration { get; set; }
    }

    private sealed class ActiveConnection
    {
        public ActiveConnection(IRelayTransport transport, string sessionId, NegotiatedRelayPolicy policy)
        {
            Transport = transport;
            SessionId = sessionId;
            Policy = policy;
            Queue = new BoundedRelayQueue<PendingSubmission>(policy.MaximumInFlight, policy.QueueOverflowPolicy);
        }

        public IRelayTransport Transport { get; }

        public string SessionId { get; }

        public NegotiatedRelayPolicy Policy { get; }

        public BoundedRelayQueue<PendingSubmission> Queue { get; }

        public SemaphoreSlim QueueSignal { get; } = new(0);

        public CancellationTokenSource Lifetime { get; } = new();

        public HashSet<string> OutstandingRequestIds { get; } = new(StringComparer.Ordinal);

        public Task[] Dispatchers { get; set; } = [];

        public ConcurrentDictionary<Task, byte> TransportOperations { get; } = new();

        public object ShutdownGate { get; } = new();

        public Task? ShutdownTask { get; set; }

        public void TrackTransportOperation(Task operation)
        {
            TransportOperations.TryAdd(operation, 0);
            _ = operation.ContinueWith(
                static (completed, state) =>
                {
                    var operations = (ConcurrentDictionary<Task, byte>)state!;
                    operations.TryRemove(completed, out _);
                },
                TransportOperations,
                CancellationToken.None,
                TaskContinuationOptions.ExecuteSynchronously,
                TaskScheduler.Default);
        }
    }

    private readonly IRelayTransportFactory _transportFactory;
    private readonly RelayOptions _options;
    private readonly IRelayClock _clock;
    private readonly IEphemeralIdentityFactory _identityFactory;
    private readonly IRelayStatusSink _statusSink;
    private readonly IRelayCueOutputAdapter _cueOutput;
    private readonly ResultCorrelator _correlator;
    private readonly SemaphoreSlim _operationGate = new(1, 1);
    private readonly object _stateGate = new();
    private readonly object _cleanupGate = new();
    private readonly HashSet<Task> _retainedCleanupTasks = [];
    private readonly TaskCompletionSource _disposalCompletion =
        new(TaskCreationOptions.RunContinuationsAsynchronously);
    private ActiveConnection? _activeConnection;
    private NegotiatedRelayPolicy? _negotiatedPolicy;
    private RelaySessionSnapshot _snapshot = new(
        RelaySessionState.Idle,
        null,
        0,
        SafeStatus.Create("idle", "Relay is idle. No capture or upload is active."));
    private int _disposeState;

    public RelaySession(
        IRelayTransportFactory transportFactory,
        RelayOptions? options = null,
        IRelayClock? clock = null,
        IEphemeralIdentityFactory? identityFactory = null,
        IRelayStatusSink? statusSink = null,
        IRelayCueOutputAdapter? cueOutput = null)
    {
        _transportFactory = transportFactory ?? throw new ArgumentNullException(nameof(transportFactory));
        _cueOutput = cueOutput ?? RejectingRelayCueOutputAdapter.Instance;
        _options = (options ?? new RelayOptions()) with
        {
            SupportedCueModalities = _cueOutput.SupportedProtocolModalities.Distinct().ToArray(),
            SupportsCueCancellation = _cueOutput.SupportsCancellation,
            SupportsCueSupersession = _cueOutput.SupportsSupersession,
        };
        _options.Validate();
        _clock = clock ?? SystemRelayClock.Instance;
        _identityFactory = identityFactory ?? new EphemeralIdentityFactory(_clock);
        _statusSink = statusSink ?? NullRelayStatusSink.Instance;
        _correlator = new ResultCorrelator(_clock, _options.MaximumResultAge, _options.QueueCapacity);
    }

    public event Action<RelaySessionSnapshot>? SnapshotChanged;

    public RelaySessionSnapshot Snapshot => Volatile.Read(ref _snapshot);

    public NegotiatedRelayPolicy? NegotiatedPolicy
    {
        get
        {
            lock (_stateGate)
            {
                return _negotiatedPolicy;
            }
        }
    }

    internal int RetainedCleanupTaskCount
    {
        get
        {
            lock (_cleanupGate)
            {
                return _retainedCleanupTasks.Count;
            }
        }
    }

    public async Task<RelaySessionSnapshot> ConnectAsync(string endpointValue, CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        var endpoint = EndpointPolicy.Validate(endpointValue, _options.AllowInsecureLoopbackForDevelopment);
        await _operationGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ThrowIfDisposed();
            var previousConnection = DeactivateConnection();
            if (previousConnection is not null)
            {
                await ShutdownConnectionAsync(previousConnection, cancellationToken).ConfigureAwait(false);
            }

            var clientInstanceId = _identityFactory.CreateDeviceInstanceId();
            ProtocolValueValidator.ValidateIdentifier(clientInstanceId, "client_instance_id");
            Exception? lastFailure = null;
            for (var attempt = 1; attempt <= _options.MaximumReconnectAttempts; attempt++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (!HasCleanupCapacity())
                {
                    lastFailure = new InvalidOperationException(
                        "Relay cleanup quarantine is full; wait for retained operations to finish before reconnecting.");
                    break;
                }
                SetSnapshot(
                    RelaySessionState.Connecting,
                    null,
                    attempt,
                    SafeStatus.Create("connecting", $"Connecting. Attempt {attempt}.", endpoint: endpoint.Uri));
                IRelayTransport? transport = null;
                ActiveConnection? activatedConnection = null;
                Task<RelayNegotiation>? connectOperation = null;
                try
                {
                    transport = _transportFactory.Create(endpoint, _options);
                    var identity = _identityFactory.CreateSessionIdentity(TimeSpan.FromMinutes(30));
                    ProtocolValueValidator.ValidateIdentifier(identity.SessionId, "identity.session_id");
                    SetSnapshot(
                        RelaySessionState.Negotiating,
                        null,
                        attempt,
                        SafeStatus.Create("negotiating", "Connected. Negotiating an ephemeral session.", endpoint: endpoint.Uri));
                    var negotiation = await TimeoutExecutor.RunAsync(
                        token => connectOperation = transport.ConnectAsync(clientInstanceId, identity, token),
                        _options.ConnectTimeout,
                        cancellationToken).ConfigureAwait(false);
                    var policy = NegotiatedRelayPolicy.Create(negotiation, _options);
                    activatedConnection = new ActiveConnection(transport, negotiation.SessionId, policy);
                    StartDispatchers(activatedConnection);
                    lock (_stateGate)
                    {
                        ThrowIfDisposed();
                        _activeConnection = activatedConnection;
                        _negotiatedPolicy = policy;
                    }

                    transport = null;
                    SetSnapshot(
                        RelaySessionState.Active,
                        negotiation.SessionId,
                        attempt,
                        SafeStatus.Create("active", "Relay connected. Capture remains off until explicitly submitted.", endpoint: endpoint.Uri));
                    return Snapshot;
                }
                catch (ObjectDisposedException) when (Volatile.Read(ref _disposeState) != 0)
                {
                    await CleanupFailedConnectionAsync(
                        transport,
                        activatedConnection,
                        connectOperation,
                        CancellationToken.None).ConfigureAwait(false);
                    throw;
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    await CleanupFailedConnectionAsync(
                        transport,
                        activatedConnection,
                        connectOperation,
                        cancellationToken).ConfigureAwait(false);
                    SetSnapshot(
                        RelaySessionState.Closed,
                        null,
                        attempt,
                        SafeStatus.Create("cancelled", "Connection cancelled. No capture was submitted.", endpoint: endpoint.Uri));
                    throw;
                }
                catch (Exception exception) when (exception is not EndpointValidationException)
                {
                    lastFailure = exception;
                    await CleanupFailedConnectionAsync(
                        transport,
                        activatedConnection,
                        connectOperation,
                        cancellationToken).ConfigureAwait(false);
                    if (attempt == _options.MaximumReconnectAttempts)
                    {
                        break;
                    }

                    var delay = ReconnectDelay(attempt);
                    SetSnapshot(
                        RelaySessionState.ReconnectBackoff,
                        null,
                        attempt,
                        SafeStatus.Create(
                            "reconnect_wait",
                            $"Connection failed safely. Retrying in {delay.TotalMilliseconds:0} ms.",
                            endpoint: endpoint.Uri));
                    try
                    {
                        await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                    {
                        SetSnapshot(
                            RelaySessionState.Closed,
                            null,
                            attempt,
                            SafeStatus.Create("cancelled", "Reconnect cancelled. No capture was submitted.", endpoint: endpoint.Uri));
                        throw;
                    }
                }
            }

            SetSnapshot(
                RelaySessionState.Failed,
                null,
                _options.MaximumReconnectAttempts,
                SafeStatus.Create(
                    "connect_failed",
                    $"Connection failed after {_options.MaximumReconnectAttempts} attempts.",
                    endpoint: endpoint.Uri));
            throw new InvalidOperationException("Relay connection attempts were exhausted.", lastFailure);
        }
        finally
        {
            _operationGate.Release();
        }
    }

    public async Task<RelayOutcome> SubmitAsync(CaptureSubmission submission, CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        ContentValidator.Validate(submission, _options);
        cancellationToken.ThrowIfCancellationRequested();

        ActiveConnection connection;
        PendingSubmission pending;
        PendingSubmission? dropped;
        QueueWriteResult<PendingSubmission> write;
        lock (_stateGate)
        {
            ThrowIfDisposed();
            connection = _activeConnection
                ?? throw new InvalidOperationException("Connect an active relay session before submitting a capture.");
            connection.Policy.Validate(submission);
            var requestId = string.IsNullOrWhiteSpace(submission.RequestId)
                ? _identityFactory.CreateRequestId()
                : submission.RequestId!;
            ProtocolValueValidator.ValidateIdentifier(requestId, "request_id");
            if (!connection.OutstandingRequestIds.Add(requestId))
            {
                throw new InvalidOperationException("The request identifier is already queued or in flight.");
            }

            var frame = CreateFrame(
                submission,
                requestId,
                connection.SessionId,
                _clock.GetMonotonicNanoseconds(),
                connection.Policy.RequestTimeout);
            pending = new PendingSubmission(frame, cancellationToken);
            write = connection.Queue.Enqueue(pending);
            dropped = write.DroppedItem;
            if (!write.Accepted)
            {
                connection.OutstandingRequestIds.Remove(requestId);
            }
            else
            {
                pending.CancellationRegistration = cancellationToken.Register(
                    () => CancelQueuedSubmission(connection, pending));
                if (pending.Completion.Task.IsCompleted)
                {
                    pending.CancellationRegistration.Dispose();
                }

                if (write.ShouldSignalConsumer)
                {
                    connection.QueueSignal.Release();
                }
            }
        }

        if (!write.Accepted)
        {
            return ReportQueueRejection(pending.Frame.RequestId, "Queue capacity reached; newest capture was rejected.");
        }

        if (dropped is not null)
        {
            CompleteDroppedSubmission(connection, dropped);
        }

        return await pending.Completion.Task.ConfigureAwait(false);
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        if (Volatile.Read(ref _disposeState) != 0)
        {
            await _disposalCompletion.Task.ConfigureAwait(false);
            return;
        }

        await _operationGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await StopCoreAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _operationGate.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.CompareExchange(ref _disposeState, 1, 0) != 0)
        {
            await _disposalCompletion.Task.ConfigureAwait(false);
            return;
        }

        try
        {
            await _operationGate.WaitAsync().ConfigureAwait(false);
            try
            {
                await StopCoreAsync(CancellationToken.None).ConfigureAwait(false);
            }
            finally
            {
                _operationGate.Release();
            }

            Volatile.Write(ref _disposeState, 2);
            _disposalCompletion.TrySetResult();
        }
        catch (Exception exception)
        {
            Volatile.Write(ref _disposeState, 2);
            _disposalCompletion.TrySetException(exception);
            throw;
        }
    }

    private void StartDispatchers(ActiveConnection connection)
    {
        connection.Dispatchers = Enumerable.Range(0, connection.Policy.MaximumInFlight)
            .Select(_ => DispatchLoopAsync(connection))
            .ToArray();
    }

    private async Task DispatchLoopAsync(ActiveConnection connection)
    {
        try
        {
            while (true)
            {
                await connection.QueueSignal.WaitAsync(connection.Lifetime.Token).ConfigureAwait(false);
                if (!connection.Queue.TryLease(out var pending) || pending is null)
                {
                    continue;
                }

                await ProcessSubmissionAsync(connection, pending).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) when (connection.Lifetime.IsCancellationRequested)
        {
            // Normal dispatcher termination after the connection lifetime is cancelled.
        }
    }

    private async Task ProcessSubmissionAsync(ActiveConnection connection, PendingSubmission pending)
    {
        var correlationPending = false;
        RelayOutcome? outcome = null;
        Exception? failure = null;
        Task? abandonedOperation = null;
        CancellationToken cancelledToken = default;
        var cancelled = false;
        try
        {
            pending.CallerCancellation.ThrowIfCancellationRequested();
            connection.Lifetime.Token.ThrowIfCancellationRequested();
            var evictedRequest = _correlator.Register(pending.Frame);
            correlationPending = true;
            if (evictedRequest is not null)
            {
                throw new InvalidOperationException("The correlation bound was exceeded by admitted work.");
            }

            _statusSink.Report(SafeStatus.Create(
                "capture_submitted",
                $"Explicitly approved capture submitted ({pending.Frame.FrameData.Length} bytes).",
                pending.Frame.RequestId));
            using var operationCancellation = CancellationTokenSource.CreateLinkedTokenSource(
                pending.CallerCancellation,
                connection.Lifetime.Token);
            var result = await TimeoutExecutor.RunAsync(
                token =>
                {
                    var operation = connection.Transport.ProcessFrameAsync(pending.Frame, token);
                    connection.TrackTransportOperation(operation);
                    return operation;
                },
                connection.Policy.RequestTimeout,
                operationCancellation.Token,
                operation => abandonedOperation = operation).ConfigureAwait(false);
            var stringValidation = ResultValidator.ValidateStrings(result, connection.Policy.MaximumCuesPerResult);
            if (!stringValidation.Accepted)
            {
                _correlator.Cancel(pending.Frame.RequestId);
                correlationPending = false;
                _statusSink.Report(SafeStatus.Create("result_rejected", stringValidation.Message, pending.Frame.RequestId));
                outcome = new RelayOutcome(
                    false,
                    pending.Frame.RequestId,
                    string.Empty,
                    stringValidation.Message,
                    stringValidation,
                    null,
                    RelayDeliveryStatus.Failed);
            }
            else
            {
                var correlation = _correlator.Accept(result);
                correlationPending = false;
                outcome = await CreateOutcomeAsync(
                    pending.Frame.RequestId,
                    result,
                    correlation,
                    connection.Policy,
                    operationCancellation.Token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) when (pending.CallerCancellation.IsCancellationRequested)
        {
            cancelled = true;
            cancelledToken = pending.CallerCancellation;
        }
        catch (OperationCanceledException) when (connection.Lifetime.IsCancellationRequested)
        {
            cancelled = true;
            cancelledToken = connection.Lifetime.Token;
        }
        catch (Exception exception)
        {
            failure = exception;
        }
        finally
        {
            if (correlationPending)
            {
                _correlator.Cancel(pending.Frame.RequestId);
            }

            pending.CancellationRegistration.Dispose();
            if (abandonedOperation is null || abandonedOperation.IsCompleted)
            {
                connection.Queue.ReleaseLease();
                ReleaseRequestId(connection, pending.Frame.RequestId);
            }
            else
            {
                ObserveBackgroundTask(ReleaseAbandonedSubmissionAsync(connection, pending, abandonedOperation));
            }
        }

        if (cancelled)
        {
            pending.Completion.TrySetCanceled(cancelledToken);
        }
        else if (failure is not null)
        {
            pending.Completion.TrySetException(failure);
        }
        else
        {
            pending.Completion.TrySetResult(outcome!);
        }
    }

    private async Task<RelayOutcome> CreateOutcomeAsync(
        string requestId,
        PerceptionResult result,
        CorrelationDecision correlation,
        NegotiatedRelayPolicy policy,
        CancellationToken cancellationToken)
    {
        if (!correlation.Accepted)
        {
            _statusSink.Report(SafeStatus.Create("result_rejected", correlation.Message, requestId));
            return new RelayOutcome(
                false,
                requestId,
                string.Empty,
                correlation.Message,
                correlation,
                null,
                RelayDeliveryStatus.Failed);
        }

        if (result.Error is { Code: not ErrorCode.Unspecified })
        {
            var errorText = $"Service rejected the frame ({result.Error.Code}).";
            _statusSink.Report(SafeStatus.Create("service_error", errorText, requestId));
            return new RelayOutcome(
                false,
                requestId,
                result.ResultId,
                errorText,
                correlation,
                null,
                RelayDeliveryStatus.Failed);
        }

        var cueValidation = CueValidator.Validate(result, _clock);
        if (!cueValidation.Accepted)
        {
            _statusSink.Report(SafeStatus.Create("cue_rejected", cueValidation.Message, requestId));
            return new RelayOutcome(
                false,
                requestId,
                result.ResultId,
                cueValidation.Message,
                cueValidation,
                null,
                RelayDeliveryStatus.Failed);
        }

        CorrelationDecision outputValidation;
        try
        {
            outputValidation = CueOutputPolicy.Validate(result, policy, _cueOutput);
        }
        catch (Exception outputInspectionFailure)
        {
            // Output adapters are an untrusted local boundary. Reject without exposing implementation details.
            _ = outputInspectionFailure;
            outputValidation = new CorrelationDecision(
                false,
                CorrelationRejection.InvalidCue,
                "The cue could not be inspected by the local output adapter.");
        }

        if (!outputValidation.Accepted)
        {
            _statusSink.Report(SafeStatus.Create("cue_output_rejected", outputValidation.Message, requestId));
            return new RelayOutcome(
                false,
                requestId,
                result.ResultId,
                outputValidation.Message,
                outputValidation,
                null,
                RelayDeliveryStatus.Failed);
        }

        var deliveredContent = false;
        var appliedControl = false;
        foreach (var cue in result.Cues)
        {
            bool delivered;
            try
            {
                delivered = await TimeoutExecutor.RunAsync(
                    token => _cueOutput.DeliverAsync(cue, token),
                    _options.OutputTimeout,
                    cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception outputFailure)
            {
                // Output adapters are an untrusted local boundary. Do not surface their exception details.
                _ = outputFailure;
                delivered = false;
            }

            if (!delivered)
            {
                const string outputFailureText = "Cue output failed safely and was not reported as delivered.";
                var outputFailure = new CorrelationDecision(
                    false,
                    CorrelationRejection.InvalidCue,
                    outputFailureText);
                _statusSink.Report(SafeStatus.Create("cue_output_failed", outputFailureText, requestId));
                return new RelayOutcome(
                    false,
                    requestId,
                    result.ResultId,
                    outputFailureText,
                    outputFailure,
                    null,
                    RelayDeliveryStatus.Failed);
            }

            if (CueOutputPolicy.IsControl(cue))
            {
                appliedControl = true;
            }
            else
            {
                deliveredContent = true;
            }
        }

        var cueText = result.Cues.Count > 0
            ? (CueOutputPolicy.IsControl(result.Cues[0]) ? "Control cue applied." : result.Cues[0].Description)
            : "Result accepted with no cue.";
        _statusSink.Report(SafeStatus.Create(
            "result_accepted",
            deliveredContent
                ? "Result accepted after validated cue output delivery."
                : appliedControl
                    ? "Result accepted after explicit control application."
                    : "Result accepted with no cue.",
            requestId));
        var delivery = deliveredContent
            ? RelayDeliveryStatus.Delivered
            : appliedControl
                ? RelayDeliveryStatus.ControlApplied
                : RelayDeliveryStatus.NotRequired;
        return new RelayOutcome(true, requestId, result.ResultId, cueText, correlation, result, delivery);
    }

    private async Task ReleaseAbandonedSubmissionAsync(
        ActiveConnection connection,
        PendingSubmission pending,
        Task operation)
    {
        try
        {
            await operation.ConfigureAwait(false);
        }
        finally
        {
            connection.Queue.ReleaseLease();
            ReleaseRequestId(connection, pending.Frame.RequestId);
        }
    }

    private void CancelQueuedSubmission(ActiveConnection connection, PendingSubmission pending)
    {
        if (!connection.Queue.TryRemove(pending))
        {
            return;
        }

        pending.CancellationRegistration.Dispose();
        ReleaseRequestId(connection, pending.Frame.RequestId);
        pending.Completion.TrySetCanceled(pending.CallerCancellation);
    }

    private void CompleteDroppedSubmission(ActiveConnection connection, PendingSubmission pending)
    {
        const string message = "Queue capacity reached; the oldest queued capture was dropped before transport.";
        var rejection = new CorrelationDecision(false, CorrelationRejection.UnknownRequest, message);
        pending.CancellationRegistration.Dispose();
        ReleaseRequestId(connection, pending.Frame.RequestId);
        pending.Completion.TrySetResult(
            new RelayOutcome(false, pending.Frame.RequestId, string.Empty, message, rejection, null));
        _statusSink.Report(SafeStatus.Create("queue_drop_oldest", message, pending.Frame.RequestId));
    }

    private RelayOutcome ReportQueueRejection(string requestId, string message)
    {
        var rejection = new CorrelationDecision(false, CorrelationRejection.UnknownRequest, message);
        _statusSink.Report(SafeStatus.Create("queue_rejected", message, requestId));
        return new RelayOutcome(false, requestId, string.Empty, message, rejection, null);
    }

    private async Task StopCoreAsync(CancellationToken cancellationToken)
    {
        var previousSessionId = Snapshot.SessionId;
        var connection = DeactivateConnection();
        SetSnapshot(
            RelaySessionState.Stopping,
            previousSessionId,
            Snapshot.Attempt,
            SafeStatus.Create("stopping", "Stopping relay and clearing queued captures."));
        try
        {
            if (connection is not null)
            {
                await ShutdownConnectionAsync(connection, cancellationToken).ConfigureAwait(false);
            }
        }
        finally
        {
            SetSnapshot(
                RelaySessionState.Closed,
                null,
                Snapshot.Attempt,
                SafeStatus.Create("closed", "Relay stopped. No capture or upload is active."));
        }
    }

    private async Task CleanupFailedConnectionAsync(
        IRelayTransport? transport,
        ActiveConnection? activatedConnection,
        Task? connectOperation,
        CancellationToken cancellationToken)
    {
        if (activatedConnection is not null)
        {
            var connection = DeactivateConnection(activatedConnection) ?? activatedConnection;
            try
            {
                await ShutdownConnectionAsync(connection, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                // The caller cancelled cleanup; the already-observed shutdown task continues safely.
            }

            return;
        }

        lock (_stateGate)
        {
            _negotiatedPolicy = null;
        }

        if (transport is not null)
        {
            var cleanupTask = DisposeTransportWhenSafeAsync(transport, connectOperation);
            ObserveBackgroundTask(cleanupTask);
            if (connectOperation is { IsCompleted: false } || cancellationToken.IsCancellationRequested)
            {
                RetainCleanupTask(cleanupTask);
                return;
            }

            await WaitForShutdownAsync(cleanupTask, cancellationToken).ConfigureAwait(false);
        }
    }

    private ActiveConnection? DeactivateConnection(ActiveConnection? expected = null)
    {
        lock (_stateGate)
        {
            if (expected is not null && !ReferenceEquals(_activeConnection, expected))
            {
                return null;
            }

            var connection = _activeConnection;
            _activeConnection = null;
            _negotiatedPolicy = null;
            return connection;
        }
    }

    private async Task ShutdownConnectionAsync(
        ActiveConnection connection,
        CancellationToken cancellationToken = default)
    {
        Task shutdownTask;
        lock (connection.ShutdownGate)
        {
            shutdownTask = connection.ShutdownTask ??= ShutdownConnectionCoreAsync(connection);
        }

        ObserveBackgroundTask(shutdownTask);
        await WaitForShutdownAsync(shutdownTask, cancellationToken).ConfigureAwait(false);
    }

    private async Task ShutdownConnectionCoreAsync(ActiveConnection connection)
    {
        var lifetimeCancellation = connection.Lifetime.CancelAsync();
        ObserveBackgroundTask(lifetimeCancellation);
        foreach (var pending in connection.Queue.Drain())
        {
            pending.CancellationRegistration.Dispose();
            ReleaseRequestId(connection, pending.Frame.RequestId);
            pending.Completion.TrySetCanceled(connection.Lifetime.Token);
        }

        Exception? shutdownFailure = null;
        try
        {
            await lifetimeCancellation.ConfigureAwait(false);
        }
        catch (Exception exception)
        {
            shutdownFailure = exception;
        }

        try
        {
            await Task.WhenAll(connection.Dispatchers).ConfigureAwait(false);
        }
        catch (Exception exception)
        {
            shutdownFailure ??= exception;
        }

        var transportOperations = connection.TransportOperations.Keys.ToArray();
        if (transportOperations.Length > 0)
        {
            try
            {
                await Task.WhenAll(transportOperations).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (connection.Lifetime.IsCancellationRequested)
            {
                // Cancellation initiated by this connection's shutdown is expected.
            }
            catch (Exception exception)
            {
                shutdownFailure ??= exception;
            }
        }

        try
        {
            await connection.Transport.DisposeAsync().ConfigureAwait(false);
        }
        catch (Exception exception)
        {
            shutdownFailure ??= exception;
        }
        finally
        {
            connection.QueueSignal.Dispose();
            connection.Lifetime.Dispose();
        }

        if (shutdownFailure is not null)
        {
            ExceptionDispatchInfo.Capture(shutdownFailure).Throw();
        }
    }

    private async Task WaitForShutdownAsync(Task shutdownTask, CancellationToken cancellationToken)
    {
        try
        {
            await TimeoutExecutor.RunAsync(
                _ => AwaitTaskAsync(shutdownTask),
                _options.ShutdownTimeout,
                cancellationToken).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            RetainCleanupTask(shutdownTask);
            _statusSink.Report(SafeStatus.Create(
                "shutdown_deferred",
                "Relay shutdown exceeded its deadline; cleanup is quarantined within a fixed bound."));
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            RetainCleanupTask(shutdownTask);
            throw;
        }
    }

    private bool HasCleanupCapacity()
    {
        lock (_cleanupGate)
        {
            return _retainedCleanupTasks.Count < _options.MaximumRetainedCleanupTasks;
        }
    }

    private void RetainCleanupTask(Task task)
    {
        if (task.IsCompleted)
        {
            return;
        }

        lock (_cleanupGate)
        {
            if (!_retainedCleanupTasks.Add(task))
            {
                return;
            }

            if (_retainedCleanupTasks.Count > _options.MaximumRetainedCleanupTasks)
            {
                _retainedCleanupTasks.Remove(task);
                throw new InvalidOperationException("Relay cleanup quarantine capacity was exceeded.");
            }
        }

        _ = task.ContinueWith(
            static (completed, state) =>
            {
                var owner = (RelaySession)state!;
                lock (owner._cleanupGate)
                {
                    owner._retainedCleanupTasks.Remove(completed);
                }
            },
            this,
            CancellationToken.None,
            TaskContinuationOptions.ExecuteSynchronously,
            TaskScheduler.Default);
    }

    private static async Task<bool> AwaitTaskAsync(Task task)
    {
        await task.ConfigureAwait(false);
        return true;
    }

    private static async Task DisposeTransportWhenSafeAsync(IRelayTransport transport, Task? activeOperation)
    {
        if (activeOperation is not null)
        {
            try
            {
                await activeOperation.ConfigureAwait(false);
            }
            catch (Exception completedOperationFailure)
            {
                // The connection attempt already reported this failure; disposal must still run.
                _ = completedOperationFailure;
            }
        }

        await transport.DisposeAsync().ConfigureAwait(false);
    }

    private static void ObserveBackgroundTask(Task task)
    {
        _ = task.ContinueWith(
            static completed => _ = completed.Exception,
            CancellationToken.None,
            TaskContinuationOptions.ExecuteSynchronously | TaskContinuationOptions.OnlyOnFaulted,
            TaskScheduler.Default);
    }

    private void ReleaseRequestId(ActiveConnection connection, string requestId)
    {
        lock (_stateGate)
        {
            connection.OutstandingRequestIds.Remove(requestId);
        }
    }

    private FramePayload CreateFrame(
        CaptureSubmission submission,
        string requestId,
        string sessionId,
        ulong captureNanoseconds,
        TimeSpan requestTimeout)
    {
        var bytes = submission.Content.ToArray();
        return new FramePayload
        {
            RequestId = requestId,
            SessionId = sessionId,
            StreamId = submission.StreamId,
            FrameId = submission.FrameId,
            CaptureMonotonicTimestampNs = captureNanoseconds,
            CaptureWallTime = Timestamp.FromDateTime(_clock.UtcNow.UtcDateTime),
            Image = new ImageDescriptor
            {
                Width = (uint)submission.Width,
                Height = (uint)submission.Height,
                Encoding = submission.Encoding,
                MediaType = submission.MediaType,
                PayloadBytes = (ulong)bytes.Length,
                Sha256 = ByteString.CopyFrom(SHA256.HashData(bytes)),
            },
            FrameData = ByteString.CopyFrom(bytes),
            ProcessingDeadline = Duration.FromTimeSpan(requestTimeout),
            Synthetic = submission.Synthetic,
        };
    }

    private TimeSpan ReconnectDelay(int failedAttempt)
    {
        var multiplier = Math.Pow(2, Math.Max(0, failedAttempt - 1));
        var milliseconds = Math.Min(
            _options.MaximumReconnectDelay.TotalMilliseconds,
            _options.InitialReconnectDelay.TotalMilliseconds * multiplier);
        return TimeSpan.FromMilliseconds(milliseconds);
    }

    private void SetSnapshot(
        RelaySessionState state,
        string? sessionId,
        int attempt,
        SafeStatus status)
    {
        var snapshot = new RelaySessionSnapshot(state, sessionId, attempt, status);
        Volatile.Write(ref _snapshot, snapshot);
        _statusSink.Report(status);
        SnapshotChanged?.Invoke(snapshot);
    }

    private void ThrowIfDisposed()
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposeState) != 0, this);
    }
}
