// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Text.Json;
using System.Text.Json.Serialization;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public sealed record QuickGlanceContextRequest(
    string SchemaVersion,
    string ContextId,
    string Purpose,
    bool ConsentGranted,
    bool SnapshotRequested);

public sealed record QuickSnipRegionRequest(
    string SchemaVersion,
    string SelectionId,
    bool ConsentGranted,
    bool UserSelected,
    CaptureRegion Region);

public sealed record QuickPubExportRequest(
    string SchemaVersion,
    string ExportId,
    string DestinationCategory,
    bool ExportApproved,
    string ResultId,
    string Summary);

public sealed record StructuredExportEnvelope(
    string SchemaVersion,
    string ExportId,
    string DestinationCategory,
    string ResultId,
    string Summary,
    bool UserApproved);

public interface IQuickGlanceRequestAdapter
{
    QuickGlanceContextRequest ParseConsentedRequest(string json);
}

public interface IQuickSnipRequestAdapter
{
    QuickSnipRegionRequest ParseUserSelectedRegion(string json, int maximumWidth, int maximumHeight);
}

public interface IQuickPubExportAdapter
{
    StructuredExportEnvelope CreateApprovedEnvelope(string json);
}

public static class QuickAdapterJson
{
    public static JsonSerializerOptions Options { get; } = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        WriteIndented = true,
    };
}

public sealed class QuickGlanceJsonAdapter : IQuickGlanceRequestAdapter
{
    public QuickGlanceContextRequest ParseConsentedRequest(string json)
    {
        var request = Deserialize<QuickGlanceContextRequest>(json);
        RequireVersion(request.SchemaVersion);
        if (!request.ConsentGranted)
        {
            throw new InvalidOperationException("QUICKGlance context or snapshot requests require explicit consent.");
        }

        if (string.IsNullOrWhiteSpace(request.ContextId) || string.IsNullOrWhiteSpace(request.Purpose))
        {
            throw new ArgumentException("QUICKGlance contextId and purpose are required.", nameof(json));
        }

        return request;
    }

    private static T Deserialize<T>(string json) where T : class =>
        JsonSerializer.Deserialize<T>(json, QuickAdapterJson.Options)
        ?? throw new JsonException("The JSON payload was empty or invalid.");

    private static void RequireVersion(string version)
    {
        if (!string.Equals(version, "1", StringComparison.Ordinal))
        {
            throw new ArgumentException("Only validating example schema version 1 is supported.");
        }
    }

    internal static T DeserializeShared<T>(string json) where T : class => Deserialize<T>(json);

    internal static void RequireVersionShared(string version) => RequireVersion(version);
}

public sealed class QuickSnipJsonAdapter : IQuickSnipRequestAdapter
{
    public QuickSnipRegionRequest ParseUserSelectedRegion(string json, int maximumWidth, int maximumHeight)
    {
        var request = QuickGlanceJsonAdapter.DeserializeShared<QuickSnipRegionRequest>(json);
        QuickGlanceJsonAdapter.RequireVersionShared(request.SchemaVersion);
        if (!request.ConsentGranted || !request.UserSelected)
        {
            throw new InvalidOperationException("QUICKSnip requires a consented, explicitly user-selected region.");
        }

        if (string.IsNullOrWhiteSpace(request.SelectionId))
        {
            throw new ArgumentException("QUICKSnip selectionId is required.", nameof(json));
        }

        ContentValidator.ValidateRegion(request.Region, maximumWidth, maximumHeight);
        return request;
    }
}

public sealed class QuickPubJsonAdapter : IQuickPubExportAdapter
{
    public StructuredExportEnvelope CreateApprovedEnvelope(string json)
    {
        var request = QuickGlanceJsonAdapter.DeserializeShared<QuickPubExportRequest>(json);
        QuickGlanceJsonAdapter.RequireVersionShared(request.SchemaVersion);
        if (!request.ExportApproved)
        {
            throw new InvalidOperationException("QUICKPub export requires explicit user approval.");
        }

        if (string.IsNullOrWhiteSpace(request.ExportId) ||
            string.IsNullOrWhiteSpace(request.DestinationCategory) ||
            string.IsNullOrWhiteSpace(request.ResultId) ||
            string.IsNullOrWhiteSpace(request.Summary))
        {
            throw new ArgumentException("Approved QUICKPub structured export fields are required.", nameof(json));
        }

        return new StructuredExportEnvelope(
            request.SchemaVersion,
            request.ExportId,
            request.DestinationCategory,
            request.ResultId,
            request.Summary,
            UserApproved: true);
    }

    public string SerializeApprovedEnvelope(StructuredExportEnvelope envelope)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        if (!envelope.UserApproved)
        {
            throw new InvalidOperationException("Unapproved exports cannot be serialized for a consumer.");
        }

        return JsonSerializer.Serialize(envelope, QuickAdapterJson.Options);
    }
}
