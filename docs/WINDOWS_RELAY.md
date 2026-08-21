<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Windows relay

The desktop relay is a .NET 8 solution with a cross-platform Core, an accessible
Windows 11 WPF shell, a headless synthetic demo, and xUnit tests. The Core can
use the canonical v1 gRPC service. The current WPF capture surface sends only a
fixed one-pixel synthetic image after one-shot approval; it does not capture the
screen, clipboard, accessibility tree, camera, or files.

## Solution map

| Project | Target | Responsibility |
| --- | --- | --- |
| `ConceptFlow.Mpl.DesktopRelay.Core` | `net8.0` | Endpoint policy, ephemeral identity, bounds, queueing, session/reconnect, timeout/cancellation, gRPC, correlation, safe status, and QUICK example adapters. |
| `ConceptFlow.Mpl.DesktopRelay.Wpf` | `net8.0-windows` | Keyboard-first shell, one-shot consent, UI Automation metadata, accessible status/error regions, and synthetic submission. |
| `ConceptFlow.Mpl.DesktopRelay.Demo` | `net8.0` | Consent-gated, no-network, screen-reader-readable synthetic vertical slice. |
| `ConceptFlow.Mpl.DesktopRelay.Core.Tests` | `net8.0` | Cross-platform xUnit tests for policy and session behavior. |

The Core generates its C# protocol client from
`packages/shared-protocol/proto/conceptflow/mpl/v1/perception.proto`; there is
no copied schema. Package versions are centralized in `Directory.Packages.props`
and NuGet lock-file restore is enabled in `Directory.Build.props`.

## Security and consent behavior

`EndpointPolicy.Validate` requires an absolute HTTPS origin without user info,
query, fragment, or application path. HTTP is allowed only for localhost or an
IP loopback when `AllowInsecureLoopbackForDevelopment` is explicitly enabled.
Certificate validation is not bypassed.

`ContentValidator.Validate` requires explicit consent and checks source,
dimensions, byte count, media type, stream, and frame identity. `RelaySession`
uses bounded queues, finite reconnect attempts, connect/request deadlines,
transport cancellation, ephemeral identities, complete correlation, and safe
status records. Capture is not automatically retried after consent is consumed.
The PNG/JPEG check here is a bounded structural preflight, not a claim of full
decodability. The Python service performs the authoritative bounded decode
before worker dispatch.

Cleanup for a cancellation-ignoring transport is quarantined after the shutdown
deadline. A relay instance retains at most `MaximumRetainedCleanupTasks` such
operations and rejects further reconnect attempts at that bound, preventing
unbounded retained cleanup ownership.

The WPF UI defaults capture to Off. Submit is disabled until the session is
active and the one-shot approval checkbox is selected. Approval is cleared
after every submission. System sounds are optional and always accompanied by
text.

## Windows build and run

Prerequisites: Windows 11, .NET 8 SDK, and network access for a first NuGet
restore. In PowerShell from the repository root:

```powershell
dotnet --info
dotnet restore .\apps\desktop-relay\ConceptFlow.Mpl.DesktopRelay.sln --locked-mode
dotnet build .\apps\desktop-relay\ConceptFlow.Mpl.DesktopRelay.sln `
  --configuration Release --no-restore
dotnet test .\apps\desktop-relay\tests\ConceptFlow.Mpl.DesktopRelay.Core.Tests\ConceptFlow.Mpl.DesktopRelay.Core.Tests.csproj `
  --configuration Release --no-build
dotnet run --project .\apps\desktop-relay\src\ConceptFlow.Mpl.DesktopRelay.Wpf\ConceptFlow.Mpl.DesktopRelay.Wpf.csproj `
  --configuration Release --no-build
```

The solution supports AnyCPU and an explicit x64 WPF configuration. Add
`-p:Platform=x64` consistently to build and run when an x64-only package is
required.

To exercise the Core without WPF or a network:

```powershell
dotnet run --project .\apps\desktop-relay\src\ConceptFlow.Mpl.DesktopRelay.Demo\ConceptFlow.Mpl.DesktopRelay.Demo.csproj `
  --configuration Release --no-build -- --consent-synthetic-demo
```

Without `--consent-synthetic-demo`, the program submits nothing and exits with
status 2 by design.

## Ubuntu cross-target validation

The Core, tests, demo, and WPF cross-target build can be checked on Ubuntu with
a .NET 8 SDK. This compiles WPF but does not execute or accessibility-test its
Windows UI.

```bash
dotnet --info
dotnet restore apps/desktop-relay/ConceptFlow.Mpl.DesktopRelay.sln --locked-mode
dotnet build apps/desktop-relay/ConceptFlow.Mpl.DesktopRelay.sln \
  --configuration Release --no-restore
dotnet test apps/desktop-relay/tests/ConceptFlow.Mpl.DesktopRelay.Core.Tests/ConceptFlow.Mpl.DesktopRelay.Core.Tests.csproj \
  --configuration Release --no-build
dotnet run --project apps/desktop-relay/src/ConceptFlow.Mpl.DesktopRelay.Demo/ConceptFlow.Mpl.DesktopRelay.Demo.csproj \
  --configuration Release --no-build -- --consent-synthetic-demo
```

## WPF operating modes

- **Deterministic in-process demo:** performs no network operation. It still
  exercises session, validation, correlation, consent, and cue presentation.
- **gRPC endpoint:** creates `GrpcRelayTransport` after endpoint validation and
  uses TLS by default. The operator must supply a real authorized service
  origin; this repository does not publish one.

The endpoint field’s local default is UI development state, not evidence that a
service is listening. Confirm the service, certificate trust, and authorization
before choosing gRPC.

## Accessibility acceptance

Run the complete Windows keyboard, Accessibility Insights/Inspect, JAWS, and
NVDA procedure in [ACCESSIBILITY.md](ACCESSIBILITY.md). At minimum verify focus
order, labels/access keys, names and HelpText, live status/error behavior,
one-shot consent, focus restoration, stop/cancellation, high contrast, 200%
scaling, and text equivalence when system sounds are disabled.

No manual Windows, JAWS, or NVDA acceptance has been completed. Cross-target
compilation is not a substitute.

## QUICK adapter boundary

`QuickAdapters.cs` contains validating JSON adapters only:

- `IQuickGlanceRequestAdapter` accepts a consented context/snapshot request;
- `IQuickSnipRequestAdapter` accepts an explicitly selected bounded region; and
- `IQuickPubExportAdapter` creates a user-approved structured export envelope
  but performs no publication.

No verified QUICKGlance or QUICKSnip transport API was found locally. The only
local QUICKPub material indicated a .NET 8 WPF, local-first, screen-reader
direction rather than a verified transport API. These adapters must remain
optional, independently operable examples. See
[QUICK_INTEGRATIONS.md](QUICK_INTEGRATIONS.md).

## Verified status

The supplied local record reports .NET 8 restore and Release build of the
solution, including WPF cross-targeting, 156 passing xUnit tests, a byte-exact
Python/C# protocol vector, and a successful consent-gated headless demo on
Ubuntu using a temporary .NET 8 SDK.

Not verified: execution on Windows, manual WPF behavior, real gRPC service use,
screen capture, JAWS, NVDA, packaging, signing, or installation. Exact evidence
and limits are in [`VALIDATION.md`](../VALIDATION.md).
