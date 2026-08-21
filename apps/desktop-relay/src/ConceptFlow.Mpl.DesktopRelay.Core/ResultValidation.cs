// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

internal static class ResultValidator
{
    internal static CorrelationDecision ValidateStrings(PerceptionResult result, int maximumCues)
    {
        try
        {
            ProtocolValueValidator.ValidateIdentifier(result.ResultId, "result_id");
            ProtocolValueValidator.ValidateIdentifier(result.RequestId, "request_id");
            ProtocolValueValidator.ValidateIdentifier(result.SessionId, "session_id");
            ProtocolValueValidator.ValidateIdentifier(result.StreamId, "stream_id");
            if (result.Observations.Count > ProtocolValueValidator.MaximumObservationsPerResult ||
                result.Cues.Count > maximumCues)
            {
                throw new ArgumentException("Result collections exceed their negotiated limits.", nameof(result));
            }

            ProtocolValueValidator.ValidateProvenance(result.Provenance, "result.provenance");
            if (result.Error is not null)
            {
                ProtocolValueValidator.ValidateText(
                    result.Error.Message,
                    "result.error.message",
                    ProtocolValueValidator.MaximumErrorScalars,
                    ProtocolValueValidator.MaximumErrorUtf8Bytes);
                ProtocolValueValidator.ValidateIdentifier(
                    result.Error.CorrelationId,
                    "result.error.correlation_id",
                    required: false);
            }

            foreach (var observation in result.Observations)
            {
                ProtocolValueValidator.ValidateIdentifier(observation.ObservationId, "observation.observation_id");
                ProtocolValueValidator.ValidateText(
                    observation.Category,
                    "observation.category",
                    ProtocolValueValidator.MaximumMetadataScalars,
                    ProtocolValueValidator.MaximumMetadataUtf8Bytes,
                    required: true);
                ProtocolValueValidator.ValidateText(
                    observation.Description,
                    "observation.description",
                    ProtocolValueValidator.MaximumDescriptionScalars,
                    ProtocolValueValidator.MaximumDescriptionUtf8Bytes);
                ProtocolValueValidator.ValidateProvenance(observation.Provenance, "observation.provenance");
            }

            foreach (var cue in result.Cues)
            {
                ValidateCueStrings(cue);
            }

            return new CorrelationDecision(true, CorrelationRejection.None, "Result strings are display-safe and bounded.");
        }
        catch (ArgumentException exception)
        {
            var cueFailure = exception.ParamName?.StartsWith("cue", StringComparison.Ordinal) == true;
            return new CorrelationDecision(
                false,
                cueFailure ? CorrelationRejection.InvalidCue : CorrelationRejection.InvalidResult,
                cueFailure
                    ? "Cue text or identifiers failed bounded protocol validation."
                    : "Result text or identifiers failed bounded protocol validation.");
        }
    }

    private static void ValidateCueStrings(PerceptionCue cue)
    {
        ProtocolValueValidator.ValidateIdentifier(cue.CueId, "cue.cue_id");
        ProtocolValueValidator.ValidateText(
            cue.Description,
            "cue.description",
            ProtocolValueValidator.MaximumDescriptionScalars,
            ProtocolValueValidator.MaximumDescriptionUtf8Bytes);
        ProtocolValueValidator.ValidateProvenance(cue.Provenance, "cue.provenance");
        if (cue.Earcon is not null)
        {
            ProtocolValueValidator.ValidateIdentifier(cue.Earcon.EarconId, "cue.earcon.earcon_id");
        }

        if (cue.Speech is not null)
        {
            ProtocolValueValidator.ValidateText(
                cue.Speech.Text,
                "cue.speech.text",
                ProtocolValueValidator.MaximumSpeechScalars,
                ProtocolValueValidator.MaximumSpeechUtf8Bytes,
                required: true);
            ProtocolValueValidator.ValidateLanguageTag(
                cue.Speech.LanguageTag,
                "cue.speech.language_tag");
        }

        if (cue.Cancel is not null)
        {
            ValidateControlStrings(cue.Cancel.CueIds, cue.Cancel.Reason, "cue.cancel");
        }

        if (cue.Supersede is not null)
        {
            ValidateControlStrings(cue.Supersede.CueIds, cue.Supersede.Reason, "cue.supersede");
        }
    }

    private static void ValidateControlStrings(
        IEnumerable<string> cueIds,
        string reason,
        string fieldName)
    {
        var boundedIds = cueIds.Take(65).ToArray();
        if (boundedIds.Length is 0 or > 64)
        {
            throw new ArgumentException($"{fieldName}.cue_ids must contain 1 through 64 identifiers.", fieldName);
        }

        foreach (var cueId in boundedIds)
        {
            ProtocolValueValidator.ValidateIdentifier(cueId, $"{fieldName}.cue_ids");
        }

        ProtocolValueValidator.ValidateText(
            reason,
            $"{fieldName}.reason",
            ProtocolValueValidator.MaximumMetadataScalars,
            ProtocolValueValidator.MaximumMetadataUtf8Bytes);
    }
}
