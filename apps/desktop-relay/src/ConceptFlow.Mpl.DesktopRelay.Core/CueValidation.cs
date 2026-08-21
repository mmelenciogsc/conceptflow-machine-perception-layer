// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

internal static class CueValidator
{
    private const ulong NanosecondsPerMillisecond = 1_000_000;

    public static CorrelationDecision Validate(PerceptionResult result, IRelayClock clock)
    {
        ArgumentNullException.ThrowIfNull(result);
        ArgumentNullException.ThrowIfNull(clock);
        if (result.Cues.Count == 0)
        {
            return Accepted();
        }

        var nowNanoseconds = clock.GetMonotonicNanoseconds();
        if (result.CompletedMonotonicTimestampNs == 0 ||
            result.CompletedMonotonicTimestampNs < result.CaptureMonotonicTimestampNs ||
            result.CompletedMonotonicTimestampNs > nowNanoseconds)
        {
            return Invalid("Result completion timestamp is invalid for its cues.");
        }

        var cueIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var cue in result.Cues)
        {
            if (!ProtocolValueValidator.IsIdentifier(cue.CueId) || cue.FrameId == 0 || !cueIds.Add(cue.CueId))
            {
                return Invalid("Cue identity is missing or duplicated.");
            }

            if (cue.FrameId != result.FrameId)
            {
                return new CorrelationDecision(
                    false,
                    CorrelationRejection.FrameMismatch,
                    "Cue frame does not match its correlated result.");
            }

            if (cue.CreatedMonotonicTimestampNs == 0 || cue.TtlMs == 0 ||
                cue.CreatedMonotonicTimestampNs < result.CaptureMonotonicTimestampNs ||
                cue.CreatedMonotonicTimestampNs > result.CompletedMonotonicTimestampNs ||
                cue.CreatedMonotonicTimestampNs > nowNanoseconds)
            {
                return Invalid("Cue timestamp or time-to-live is invalid.");
            }

            var ageNanoseconds = nowNanoseconds - cue.CreatedMonotonicTimestampNs;
            var lifetimeNanoseconds = (ulong)cue.TtlMs * NanosecondsPerMillisecond;
            if (ageNanoseconds >= lifetimeNanoseconds)
            {
                return new CorrelationDecision(false, CorrelationRejection.Stale, "Cue expired before it could be surfaced.");
            }

            if (!double.IsFinite(cue.Confidence) || cue.Confidence is < 0 or > 1)
            {
                return Invalid("Cue confidence must be finite and within the inclusive range from zero to one.");
            }
        }

        return Accepted();
    }

    private static CorrelationDecision Accepted() =>
        new(true, CorrelationRejection.None, "Cues are valid for the correlated result.");

    private static CorrelationDecision Invalid(string message) =>
        new(false, CorrelationRejection.InvalidCue, message);
}
