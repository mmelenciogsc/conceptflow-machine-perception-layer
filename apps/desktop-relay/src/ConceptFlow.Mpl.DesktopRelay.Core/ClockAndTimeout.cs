// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Diagnostics;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public interface IRelayClock
{
    DateTimeOffset UtcNow { get; }

    long GetTimestamp();

    TimeSpan GetElapsedTime(long startingTimestamp);

    ulong GetMonotonicNanoseconds();
}

public sealed class SystemRelayClock : IRelayClock
{
    public static SystemRelayClock Instance { get; } = new();

    private SystemRelayClock()
    {
    }

    public DateTimeOffset UtcNow => DateTimeOffset.UtcNow;

    public long GetTimestamp() => Stopwatch.GetTimestamp();

    public TimeSpan GetElapsedTime(long startingTimestamp) => Stopwatch.GetElapsedTime(startingTimestamp);

    public ulong GetMonotonicNanoseconds()
    {
        var timestamp = Stopwatch.GetTimestamp();
        return checked((ulong)((decimal)timestamp * 1_000_000_000m / Stopwatch.Frequency));
    }
}

public static class TimeoutExecutor
{
    public static async Task<T> RunAsync<T>(
        Func<CancellationToken, Task<T>> operation,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        return await RunAsync(operation, timeout, cancellationToken, null).ConfigureAwait(false);
    }

    internal static async Task<T> RunAsync<T>(
        Func<CancellationToken, Task<T>> operation,
        TimeSpan timeout,
        CancellationToken cancellationToken,
        Action<Task>? abandonedOperation)
    {
        ArgumentNullException.ThrowIfNull(operation);
        if (timeout <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(timeout));
        }

        cancellationToken.ThrowIfCancellationRequested();
        var operationCancellation = new CancellationTokenSource();
        using var timeoutWaitCancellation = new CancellationTokenSource();
        var cancellationSignal = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var cancellationRegistration = cancellationToken.Register(
            static state => ((TaskCompletionSource)state!).TrySetResult(),
            cancellationSignal);
        Task<T> operationTask;
        try
        {
            operationTask = operation(operationCancellation.Token)
                ?? throw new InvalidOperationException("The operation returned no task.");
        }
        catch
        {
            operationCancellation.Dispose();
            throw;
        }

        var timeoutTask = Task.Delay(timeout, timeoutWaitCancellation.Token);
        var cancellationTask = cancellationSignal.Task;
        var completedTask = await Task.WhenAny(operationTask, timeoutTask, cancellationTask).ConfigureAwait(false);
        CancelWithoutThrowing(timeoutWaitCancellation);
        if (ReferenceEquals(completedTask, operationTask))
        {
            cancellationRegistration.Dispose();
            operationCancellation.Dispose();
            return await operationTask.ConfigureAwait(false);
        }

        var cancellationRequest = RequestCancellation(operationCancellation);
        ObserveFailure(cancellationRequest);
        cancellationRegistration.Dispose();
        if (!operationTask.IsCompleted)
        {
            ObserveFailure(operationTask);
            abandonedOperation?.Invoke(operationTask);
        }

        DisposeWhenCompleted(Task.WhenAll(operationTask, cancellationRequest), operationCancellation);

        if (cancellationToken.IsCancellationRequested)
        {
            throw new OperationCanceledException(cancellationToken);
        }

        Exception? cancellationFailure = null;
        if (operationTask.IsCompleted)
        {
            try
            {
                await operationTask.ConfigureAwait(false);
            }
            catch (Exception exception)
            {
                cancellationFailure = exception;
            }
        }

        throw new TimeoutException(
            $"The operation exceeded its {timeout.TotalMilliseconds:0} ms deadline.",
            cancellationFailure);
    }

    private static void ObserveFailure(Task task)
    {
        _ = task.ContinueWith(
            static completed => _ = completed.Exception,
            CancellationToken.None,
            TaskContinuationOptions.ExecuteSynchronously | TaskContinuationOptions.OnlyOnFaulted,
            TaskScheduler.Default);
    }

    private static void DisposeWhenCompleted(Task task, CancellationTokenSource cancellationSource)
    {
        _ = task.ContinueWith(
            static (completed, state) =>
            {
                _ = completed.Exception;
                ((CancellationTokenSource)state!).Dispose();
            },
            cancellationSource,
            CancellationToken.None,
            TaskContinuationOptions.ExecuteSynchronously,
            TaskScheduler.Default);
    }

    private static Task RequestCancellation(CancellationTokenSource cancellationSource)
    {
        try
        {
            return cancellationSource.CancelAsync();
        }
        catch (ObjectDisposedException)
        {
            return Task.CompletedTask;
        }
    }

    private static void CancelWithoutThrowing(CancellationTokenSource cancellationSource)
    {
        try
        {
            cancellationSource.Cancel();
        }
        catch (AggregateException cancellationFailures)
        {
            // Cancel aggregates callback failures. Consume only expected cancellation races;
            // AggregateException.Handle rethrows any unexpected callback exception.
            cancellationFailures.Flatten().Handle(
                static failure => failure is OperationCanceledException or ObjectDisposedException);
        }
        catch (ObjectDisposedException disposedCancellationSource)
        {
            // Completion can win the race and dispose this best-effort cancellation source.
            _ = disposedCancellationSource;
        }
    }
}
