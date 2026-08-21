<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# QUICK integrations

MPL can interoperate with QUICK-family tools through small, consent-aware
adapter boundaries. These are validating example semantics, not claims about a
published QUICKGlance, QUICKSnip, or QUICKPub transport API.

No verified QUICKGlance or QUICKSnip API source was found locally. The only
identified QUICKPub source, `QUICKPub_Codex_Implementation_Manual_v2.txt`,
described a .NET 8 WPF, local-first, screen-reader direction but did not provide
a verified transport contract. No dependency on that file or on any QUICK app
exists in this repository.

## Independence requirement

MPL, QUICKGlance, QUICKSnip, and QUICKPub must each remain independently
operable. Installing, launching, connecting, or consenting in one must not be a
hidden prerequisite for another. Adapters translate explicit user intent at a
process boundary; they do not merge lifecycle, storage, telemetry, identity, or
permissions.

If an adapter is absent, MPL’s synthetic demo, Android apps, service, and
desktop relay continue to function within their documented bounds.

## Implemented adapter interfaces

`apps/desktop-relay/src/ConceptFlow.Mpl.DesktopRelay.Core/QuickAdapters.cs`
defines:

- `IQuickGlanceRequestAdapter.ParseConsentedRequest` for a user-consented
  context/snapshot request producer;
- `IQuickSnipRequestAdapter.ParseUserSelectedRegion` for a consented,
  explicitly selected, bounded-region capture producer; and
- `IQuickPubExportAdapter.CreateApprovedEnvelope` for an explicitly approved
  structured export consumer.

The JSON adapters require schema version `1`, reject unknown fields, validate
required identifiers and intent flags, and validate region geometry where
applicable. `QuickPubJsonAdapter` creates and serializes an inspectable envelope
but performs no network, filesystem, clipboard, or publication operation.

## Example semantics

These records are examples owned by this repository, not vendor wire formats.

### QUICKGlance producer

```json
{
  "schemaVersion": "1",
  "contextId": "context-example",
  "purpose": "user-requested scene context",
  "consentGranted": true,
  "snapshotRequested": true
}
```

Interpretation: a user has asked for a context/snapshot operation for the named
purpose. The adapter validates intent only. A capture implementation must still
apply MPL content bounds, current permission, transport policy, and a visible
capture state.

### QUICKSnip producer

```json
{
  "schemaVersion": "1",
  "selectionId": "selection-example",
  "consentGranted": true,
  "userSelected": true,
  "region": { "x": 10, "y": 20, "width": 200, "height": 100 }
}
```

Interpretation: the user selected a bounded region on a caller-defined capture
surface. `ContentValidator.ValidateRegion` checks positive dimensions and
containment. The example does not read or capture screen pixels.

### QUICKPub consumer

```json
{
  "schemaVersion": "1",
  "exportId": "export-example",
  "destinationCategory": "user-selected structured consumer",
  "exportApproved": true,
  "resultId": "result-example",
  "summary": "Synthetic cue summary."
}
```

Interpretation: the user approved creation of a structured envelope for a
selected consumer category. The adapter does not publish. A real exporter must
ask for the destination, display exactly what will leave the process, and
report success/failure accessibly.

Setting the relevant consent, selection, or approval flag to `false` causes the
corresponding adapter to reject the operation.

## Integration rules

1. Keep the adapter package optional and dependency-inverted.
2. Version the real contract independently once an authoritative API exists;
   do not silently treat these example JSON records as that contract.
3. Carry purpose, consent, and synthetic status through the operation without
   translating them into durable identity.
4. Apply MPL size, dimension, rate, queue, timeout, correlation, and freshness
   limits after adapter validation.
5. Do not grant continuous capture from a one-shot request or publication from
   capture consent.
6. Keep local-first processing available. Any external model or export route
   requires separate, explicit approval.
7. Return accessible text state for accepted, rejected, cancelled, expired, and
   failed actions.
8. Do not log raw JSON if it may contain user content. Safe status should retain
   only bounded identifiers and redacted state.

## Validation

The .NET xUnit suite covers consent rejection and accepted example parsing for
QUICKGlance, explicit bounded selection for QUICKSnip, and approval-gated
envelope creation for QUICKPub. This validates the local adapter semantics only;
it does not test an external QUICK process, transport, or compatibility claim.

See [WINDOWS_RELAY.md](WINDOWS_RELAY.md),
[PRIVACY_ARCHITECTURE.md](PRIVACY_ARCHITECTURE.md), and
[PROTOCOL.md](PROTOCOL.md).
