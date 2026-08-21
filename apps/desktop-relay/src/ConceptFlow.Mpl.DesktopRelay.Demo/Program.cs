// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.DesktopRelay.Core;
using ConceptFlow.Mpl.V1;

if (!args.Contains("--consent-synthetic-demo", StringComparer.Ordinal))
{
    Console.Error.WriteLine("No capture submitted. Re-run with --consent-synthetic-demo to explicitly approve the synthetic in-process demonstration.");
    return 2;
}

var options = new RelayOptions
{
    ConnectTimeout = TimeSpan.FromSeconds(2),
    RequestTimeout = TimeSpan.FromSeconds(2),
};
var sink = new ConsoleStatusSink();
var cueOutput = new ConsoleSpeechCueOutputAdapter();
await using var session = new RelaySession(
    new InProcessRelayTransportFactory(),
    options,
    statusSink: sink,
    cueOutput: cueOutput);

Console.WriteLine("CONCEPTFlow desktop relay headless demonstration.");
Console.WriteLine("This sends only a fixed synthetic one-pixel image to an in-process mock. It does not capture the screen or use a network.");
await session.ConnectAsync("https://localhost:7443");

var onePixelPng = SyntheticCaptureAssets.CreateOnePixelPng();
var outcome = await session.SubmitAsync(new CaptureSubmission(
    Source: "synthetic-demo",
    Content: onePixelPng,
    Width: 1,
    Height: 1,
    Encoding: ImageEncoding.Png,
    MediaType: "image/png",
    ConsentGranted: true,
    Synthetic: true));

Console.WriteLine($"Outcome: {(outcome.Accepted ? "accepted" : "rejected")}.");
Console.WriteLine($"Request: {outcome.RequestId}.");
Console.WriteLine($"Result: {outcome.ResultId}.");
Console.WriteLine($"Inspectable cue: {outcome.Text}");
return outcome.Accepted ? 0 : 1;

internal sealed class ConsoleStatusSink : IRelayStatusSink
{
    public void Report(SafeStatus status) =>
        Console.WriteLine($"Status {status.Code}: {status.Message}");
}

internal sealed class ConsoleSpeechCueOutputAdapter : IRelayCueOutputAdapter
{
    public IReadOnlyCollection<CueModality> SupportedProtocolModalities { get; } = [CueModality.Speech];

    public bool SupportsCancellation => false;

    public bool SupportsSupersession => false;

    public bool CanDeliver(PerceptionCue cue) => cue.Speech is not null;

    public Task<bool> DeliverAsync(PerceptionCue cue, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        Console.WriteLine($"Protocol speech text: {cue.Speech.Text}");
        return Task.FromResult(true);
    }
}
