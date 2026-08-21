// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Buffers;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

internal static partial class ProtocolValueValidator
{
    internal const int MaximumIdentifierLength = 64;
    internal const int MaximumDescriptionScalars = 256;
    internal const int MaximumDescriptionUtf8Bytes = 768;
    internal const int MaximumSpeechScalars = 256;
    internal const int MaximumSpeechUtf8Bytes = 768;
    internal const int MaximumMetadataScalars = 128;
    internal const int MaximumMetadataUtf8Bytes = 384;
    internal const int MaximumErrorScalars = 256;
    internal const int MaximumErrorUtf8Bytes = 768;
    internal const int MaximumObservationsPerResult = 128;
    internal const int MaximumSourceResultIds = 64;

    internal static bool IsIdentifier(string? value) =>
        value is not null && IdentifierPattern().IsMatch(value);

    internal static void ValidateIdentifier(string? value, string fieldName, bool required = true)
    {
        if (string.IsNullOrEmpty(value))
        {
            if (required)
            {
                throw new ArgumentException($"{fieldName} is required.", fieldName);
            }

            return;
        }

        if (!IsIdentifier(value))
        {
            throw new ArgumentException(
                $"{fieldName} must use the canonical opaque identifier format.",
                fieldName);
        }
    }

    internal static void ValidateLanguageTag(string? value, string fieldName)
    {
        if (!string.IsNullOrEmpty(value) && !LanguageTagPattern().IsMatch(value))
        {
            throw new ArgumentException($"{fieldName} must be a bounded language tag.", fieldName);
        }
    }

    internal static void ValidateText(
        string? value,
        string fieldName,
        int maximumScalars,
        int maximumUtf8Bytes,
        bool required = false)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(maximumScalars, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(maximumUtf8Bytes, 1);
        if (string.IsNullOrEmpty(value))
        {
            if (required)
            {
                throw new ArgumentException($"{fieldName} is required.", fieldName);
            }

            return;
        }

        if (required && string.IsNullOrWhiteSpace(value))
        {
            throw new ArgumentException($"{fieldName} must contain visible text.", fieldName);
        }

        var remaining = value.AsSpan();
        var scalarCount = 0;
        while (!remaining.IsEmpty)
        {
            var status = Rune.DecodeFromUtf16(remaining, out var rune, out var consumed);
            if (status != OperationStatus.Done)
            {
                throw new ArgumentException($"{fieldName} contains invalid Unicode.", fieldName);
            }

            scalarCount++;
            if (scalarCount > maximumScalars || IsUnsafeForDisplay(Rune.GetUnicodeCategory(rune)))
            {
                throw new ArgumentException($"{fieldName} exceeds its display-safe limits.", fieldName);
            }

            remaining = remaining[consumed..];
        }

        if (Encoding.UTF8.GetByteCount(value) > maximumUtf8Bytes)
        {
            throw new ArgumentException($"{fieldName} exceeds its UTF-8 byte limit.", fieldName);
        }
    }

    internal static void ValidateProvenance(Provenance? provenance, string fieldName)
    {
        if (provenance is null)
        {
            return;
        }

        ValidateIdentifier(provenance.Component, $"{fieldName}.component", required: false);
        ValidateIdentifier(provenance.ComponentVersion, $"{fieldName}.component_version", required: false);
        ValidateIdentifier(provenance.WorkerId, $"{fieldName}.worker_id", required: false);
        ValidateIdentifier(provenance.ModelId, $"{fieldName}.model_id", required: false);
        ValidateIdentifier(provenance.ModelVersion, $"{fieldName}.model_version", required: false);
        ValidateIdentifier(provenance.ArtifactDigest, $"{fieldName}.artifact_digest", required: false);
        if (provenance.SourceResultIds.Count > MaximumSourceResultIds)
        {
            throw new ArgumentException($"{fieldName}.source_result_ids exceeds its item limit.", fieldName);
        }

        foreach (var sourceResultId in provenance.SourceResultIds)
        {
            ValidateIdentifier(sourceResultId, $"{fieldName}.source_result_ids");
        }
    }

    private static bool IsUnsafeForDisplay(UnicodeCategory category) => category is
        UnicodeCategory.Control or
        UnicodeCategory.Format or
        UnicodeCategory.Surrogate or
        UnicodeCategory.PrivateUse or
        UnicodeCategory.OtherNotAssigned or
        UnicodeCategory.LineSeparator or
        UnicodeCategory.ParagraphSeparator;

    [GeneratedRegex("\\A[A-Za-z0-9][A-Za-z0-9._:-]{0,63}\\z", RegexOptions.CultureInvariant)]
    private static partial Regex IdentifierPattern();

    [GeneratedRegex("\\A[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8}){0,4}\\z", RegexOptions.CultureInvariant)]
    private static partial Regex LanguageTagPattern();
}

internal static class CaptureSourcePolicy
{
    private static readonly HashSet<string> AllowedSources = new(StringComparer.Ordinal)
    {
        "synthetic-demo",
        "user-selected-region",
        "user-selected-screen",
        "user-selected-window",
    };

    internal static void Validate(string? source)
    {
        if (source is null || !AllowedSources.Contains(source))
        {
            throw new ArgumentException("Capture source must be an approved local category.", nameof(source));
        }
    }
}
