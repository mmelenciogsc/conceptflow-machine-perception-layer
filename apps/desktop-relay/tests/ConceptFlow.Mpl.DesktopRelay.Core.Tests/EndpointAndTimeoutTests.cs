// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.DesktopRelay.Core;

namespace ConceptFlow.Mpl.DesktopRelay.Core.Tests;

public sealed class EndpointAndTimeoutTests
{
    [Theory]
    [InlineData("")]
    [InlineData("not a URI")]
    [InlineData("ftp://localhost:21")]
    [InlineData("http://relay.example.invalid:7443")]
    [InlineData("http://localhost:7443")]
    [InlineData("https://user:sample@relay.example.invalid")]
    [InlineData("https://relay.example.invalid/path?token=sample")]
    public void Validate_RejectsMalformedOrUnsafeEndpoints(string endpoint)
    {
        Assert.Throws<EndpointValidationException>(() => EndpointPolicy.Validate(endpoint));
    }

    [Fact]
    public void Validate_AcceptsHttpsByDefault()
    {
        var endpoint = EndpointPolicy.Validate("https://perception.example.test:7443");

        Assert.True(endpoint.UsesTls);
        Assert.False(endpoint.IsDevelopmentLoopback);
    }

    [Theory]
    [InlineData("http://localhost:7443")]
    [InlineData("http://127.0.0.1:7443")]
    [InlineData("http://[::1]:7443")]
    public void Validate_AllowsOnlyExplicitDevelopmentLoopback(string value)
    {
        var endpoint = EndpointPolicy.Validate(value, allowInsecureLoopbackForDevelopment: true);

        Assert.False(endpoint.UsesTls);
        Assert.True(endpoint.IsDevelopmentLoopback);
    }

    [Fact]
    public async Task TimeoutExecutor_ReportsDeadlineInsteadOfCancellation()
    {
        var error = await Assert.ThrowsAsync<TimeoutException>(() =>
            TimeoutExecutor.RunAsync(
                async token =>
                {
                    await Task.Delay(Timeout.InfiniteTimeSpan, token);
                    return 1;
                },
                TimeSpan.FromMilliseconds(20),
                CancellationToken.None));

        Assert.Contains("deadline", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task TimeoutExecutor_EnforcesDeadlineWhenOperationIgnoresCancellation()
    {
        var operation = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);

        var error = await Assert.ThrowsAsync<TimeoutException>(() =>
            TimeoutExecutor.RunAsync(
                _ => operation.Task,
                TimeSpan.FromMilliseconds(20),
                CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(1)));

        Assert.Contains("deadline", error.Message, StringComparison.OrdinalIgnoreCase);
        Assert.False(operation.Task.IsCompleted);
        operation.TrySetException(new IOException("synthetic abandoned operation failure"));
    }

    [Fact]
    public async Task TimeoutExecutor_EnforcesCallerCancellationWhenOperationIgnoresCancellation()
    {
        var operation = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);
        using var cancellation = new CancellationTokenSource();
        var execution = TimeoutExecutor.RunAsync(
            _ => operation.Task,
            TimeSpan.FromSeconds(5),
            cancellation.Token);

        cancellation.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            execution.WaitAsync(TimeSpan.FromSeconds(1)));
        Assert.False(operation.Task.IsCompleted);
        operation.TrySetResult(1);
    }
}
