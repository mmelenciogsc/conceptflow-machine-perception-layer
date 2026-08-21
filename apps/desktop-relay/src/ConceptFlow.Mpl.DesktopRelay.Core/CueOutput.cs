// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public interface IRelayCueOutputAdapter
{
    IReadOnlyCollection<CueModality> SupportedProtocolModalities { get; }

    bool SupportsCancellation { get; }

    bool SupportsSupersession { get; }

    bool CanDeliver(PerceptionCue cue);

    Task<bool> DeliverAsync(PerceptionCue cue, CancellationToken cancellationToken);
}

public enum CueAttention
{
    Polite,
    Assertive,
}

public static class CueAttentionPolicy
{
    public static CueAttention For(PerceptionCue cue)
    {
        ArgumentNullException.ThrowIfNull(cue);
        return cue.Speech?.Interrupt == true || cue.Urgency is Urgency.High or Urgency.Critical
            ? CueAttention.Assertive
            : CueAttention.Polite;
    }
}

public sealed class RejectingRelayCueOutputAdapter : IRelayCueOutputAdapter
{
    public static RejectingRelayCueOutputAdapter Instance { get; } = new();

    private RejectingRelayCueOutputAdapter()
    {
    }

    public IReadOnlyCollection<CueModality> SupportedProtocolModalities => Array.Empty<CueModality>();

    public bool SupportsCancellation => false;

    public bool SupportsSupersession => false;

    public bool CanDeliver(PerceptionCue cue) => false;

    public Task<bool> DeliverAsync(PerceptionCue cue, CancellationToken cancellationToken) =>
        Task.FromResult(false);
}

internal static class CueOutputPolicy
{
    internal static CorrelationDecision Validate(
        PerceptionResult result,
        NegotiatedRelayPolicy policy,
        IRelayCueOutputAdapter output)
    {
        foreach (var cue in result.Cues)
        {
            var hasCancellation = cue.Cancel is not null;
            var hasSupersession = cue.Supersede is not null;
            if (hasCancellation || hasSupersession)
            {
                if (hasCancellation == hasSupersession || cue.Category != CueCategory.System ||
                    cue.Earcon is not null || cue.Speech is not null || cue.Haptic is not null ||
                    !string.IsNullOrEmpty(cue.Description))
                {
                    return Invalid("Control cues must be explicit, system-category, and control-only.");
                }

                if ((hasCancellation && (!policy.SupportsCancellation || !output.SupportsCancellation)) ||
                    (hasSupersession && (!policy.SupportsSupersession || !output.SupportsSupersession)))
                {
                    return Invalid("The control cue is not supported by the negotiated local output.");
                }
            }
            else
            {
                var modalities = PresentModalities(cue);
                if (modalities.Count == 0 ||
                    modalities.Any(modality =>
                        !policy.SupportedCueModalities.Contains(modality) ||
                        !output.SupportedProtocolModalities.Contains(modality)) ||
                    !output.CanDeliver(cue))
                {
                    return Invalid("The cue has no negotiated, locally renderable modality.");
                }
            }
        }

        return new CorrelationDecision(true, CorrelationRejection.None, "Cue output is locally renderable.");
    }

    internal static bool IsControl(PerceptionCue cue) => cue.Cancel is not null || cue.Supersede is not null;

    private static IReadOnlyList<CueModality> PresentModalities(PerceptionCue cue)
    {
        var modalities = new List<CueModality>(3);
        if (cue.Earcon is not null)
        {
            modalities.Add(CueModality.Earcon);
        }

        if (cue.Speech is not null)
        {
            modalities.Add(CueModality.Speech);
        }

        if (cue.Haptic is not null)
        {
            modalities.Add(CueModality.Haptic);
        }

        return modalities;
    }

    private static CorrelationDecision Invalid(string message) =>
        new(false, CorrelationRejection.InvalidCue, message);
}
