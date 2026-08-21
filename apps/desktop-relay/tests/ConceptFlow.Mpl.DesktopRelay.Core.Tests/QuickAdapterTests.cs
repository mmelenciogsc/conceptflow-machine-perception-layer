// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.DesktopRelay.Core;

namespace ConceptFlow.Mpl.DesktopRelay.Core.Tests;

public sealed class QuickAdapterTests
{
    [Fact]
    public void QuickGlance_RequiresExplicitConsent()
    {
        const string json = """
            {
              "schemaVersion": "1",
              "contextId": "context-example",
              "purpose": "user-requested scene context",
              "consentGranted": false,
              "snapshotRequested": true
            }
            """;

        var error = Assert.Throws<InvalidOperationException>(() =>
            new QuickGlanceJsonAdapter().ParseConsentedRequest(json));

        Assert.Contains("consent", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void QuickGlance_AcceptsConsentedExamplePayload()
    {
        const string json = """
            {
              "schemaVersion": "1",
              "contextId": "context-example",
              "purpose": "user-requested scene context",
              "consentGranted": true,
              "snapshotRequested": true
            }
            """;

        var request = new QuickGlanceJsonAdapter().ParseConsentedRequest(json);

        Assert.True(request.ConsentGranted);
        Assert.True(request.SnapshotRequested);
    }

    [Fact]
    public void QuickSnip_RequiresExplicitSelectionAndBoundedRegion()
    {
        const string json = """
            {
              "schemaVersion": "1",
              "selectionId": "selection-example",
              "consentGranted": true,
              "userSelected": false,
              "region": { "x": 10, "y": 20, "width": 200, "height": 100 }
            }
            """;

        Assert.Throws<InvalidOperationException>(() =>
            new QuickSnipJsonAdapter().ParseUserSelectedRegion(json, 1920, 1080));
    }

    [Fact]
    public void QuickSnip_AcceptsExplicitBoundedSelection()
    {
        const string json = """
            {
              "schemaVersion": "1",
              "selectionId": "selection-example",
              "consentGranted": true,
              "userSelected": true,
              "region": { "x": 10, "y": 20, "width": 200, "height": 100 }
            }
            """;

        var request = new QuickSnipJsonAdapter().ParseUserSelectedRegion(json, 1920, 1080);

        Assert.Equal(200, request.Region.Width);
    }

    [Fact]
    public void QuickPub_RefusesUnapprovedExport()
    {
        const string json = """
            {
              "schemaVersion": "1",
              "exportId": "export-example",
              "destinationCategory": "user-selected structured consumer",
              "exportApproved": false,
              "resultId": "result-example",
              "summary": "Synthetic cue summary."
            }
            """;

        Assert.Throws<InvalidOperationException>(() =>
            new QuickPubJsonAdapter().CreateApprovedEnvelope(json));
    }

    [Fact]
    public void QuickPub_CreatesInspectableEnvelopeButDoesNotPublish()
    {
        const string json = """
            {
              "schemaVersion": "1",
              "exportId": "export-example",
              "destinationCategory": "user-selected structured consumer",
              "exportApproved": true,
              "resultId": "result-example",
              "summary": "Synthetic cue summary."
            }
            """;
        var adapter = new QuickPubJsonAdapter();

        var envelope = adapter.CreateApprovedEnvelope(json);
        var serialized = adapter.SerializeApprovedEnvelope(envelope);

        Assert.True(envelope.UserApproved);
        Assert.Contains("Synthetic cue summary", serialized, StringComparison.Ordinal);
    }
}
