<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# CONCEPTFlow desktop relay

This packet provides a maintainable .NET 8 desktop relay with a pure,
cross-platform Core, a keyboard-first Windows 11 WPF shell, Linux-compatible
xUnit tests, and a screen-reader-consumable headless demonstration. The
protocol classes and gRPC client are generated from the repository's canonical
[`perception.proto`](../../packages/shared-protocol/proto/conceptflow/mpl/v1/perception.proto);
the schema is not copied into this application.

The supported Windows target is Windows 11 with at least 8 GB RAM. That is a
documented support baseline, not a runtime memory gate. The WPF project supports
AnyCPU and an explicit x64 configuration.

## Safety and privacy behavior

- Capture starts off and remains off until a user connects, checks the
  one-submission consent control, and activates **Submit approved snapshot**.
- The current UI submits only a fixed synthetic one-pixel PNG. It does not read
  the screen, clipboard, camera, files, or accessibility tree.
- Consent is consumed after one submission. There is no continuous capture,
  background capture, automatic upload, telemetry, or automatic publication.
- HTTPS/TLS is required by default. Plain HTTP is accepted only for `localhost`,
  `127.0.0.1`, or `::1` when the visible development checkbox is explicitly
  enabled. Certificate validation is never bypassed.
- gRPC send/receive message sizes, frame sizes, dimensions, queues, deadlines,
  retries, and result age are bounded. Negotiated encodings, dimensions, frame
  bytes, deadlines, and max-in-flight are intersected with stricter local
  limits before admission; zero or unusable negotiated limits fail the
  connection. Caller cancellation reaches transport operations and releases
  bounded admission capacity.
- Device, session, nonce, and request identifiers are ephemeral. Safe status
  records omit payloads, paths, query strings, credentials, and common secret
  shapes.
- The deterministic in-process mode uses the same session, validation,
  correlation, and cue path as gRPC, but performs no network operation.

The relay is supplemental environmental-awareness infrastructure. It is not an
autonomous safety authority and does not replace a mobility aid, trained
judgment, or an emergency system.

## Projects

- `src/ConceptFlow.Mpl.DesktopRelay.Core`: `net8.0`, no WPF dependency. Contains
  endpoint policy, bounded queues, state/reconnect logic, content validation,
  timeout and cancellation propagation, protobuf/gRPC transport, correlation,
  safe status, QUICK-family adapter boundaries, and the deterministic mock.
- `src/ConceptFlow.Mpl.DesktopRelay.Wpf`: `net8.0-windows` WPF application with
  stock controls, explicit labels, deterministic tab order, UI Automation
  names/help/live settings, textual state/errors, and focus restoration.
- `src/ConceptFlow.Mpl.DesktopRelay.Demo`: `net8.0` headless demonstration.
- `tests/ConceptFlow.Mpl.DesktopRelay.Core.Tests`: `net8.0` xUnit tests that run
  on Linux without WPF.

Dependency versions are pinned in `Directory.Packages.props`, NuGet lock-file
generation is enabled in `Directory.Build.props`, and locked restores are used
for release validation.

## Linux restore, Core test, and headless demo

Run from `apps/desktop-relay` with a .NET 8 SDK:

```bash
dotnet --info
dotnet restore ConceptFlow.Mpl.DesktopRelay.sln --locked-mode
dotnet build ConceptFlow.Mpl.DesktopRelay.sln --configuration Release --no-restore
dotnet test tests/ConceptFlow.Mpl.DesktopRelay.Core.Tests/ConceptFlow.Mpl.DesktopRelay.Core.Tests.csproj --configuration Release --no-restore
dotnet run --project src/ConceptFlow.Mpl.DesktopRelay.Demo/ConceptFlow.Mpl.DesktopRelay.Demo.csproj --configuration Release --no-restore -- --consent-synthetic-demo
```

Running the demo without `--consent-synthetic-demo` exits without submitting
anything. It prints each state and the inspectable synthetic cue as plain text.

## Windows restore, build, test, and run

Use PowerShell from `apps\desktop-relay` on Windows 11 with a .NET 8 SDK:

```powershell
dotnet --info
dotnet restore .\ConceptFlow.Mpl.DesktopRelay.sln --locked-mode
dotnet build .\ConceptFlow.Mpl.DesktopRelay.sln --configuration Release -p:Platform=x64 --no-restore
dotnet test .\tests\ConceptFlow.Mpl.DesktopRelay.Core.Tests\ConceptFlow.Mpl.DesktopRelay.Core.Tests.csproj --configuration Release --no-restore
dotnet run --project .\src\ConceptFlow.Mpl.DesktopRelay.Wpf\ConceptFlow.Mpl.DesktopRelay.Wpf.csproj --configuration Release -p:Platform=x64 --no-restore
```

For AnyCPU, omit `-p:Platform=x64`. The WPF project sets
`EnableWindowsTargeting=true`, while Core, Demo, and tests remain plain
cross-platform `net8.0` projects.

## Keyboard, JAWS/NVDA, and UI Automation manual checks

Perform these checks with both current JAWS and NVDA. Do not use real or private
content; the built-in synthetic snapshot is sufficient.

1. Launch with the screen reader already running. Confirm the window name and
   the statement that capture is off are announced. No sound, connection, or
   submission should occur on launch.
2. Press `Tab` repeatedly. Verify the deterministic order: transport mode,
   endpoint, insecure-loopback option, Connect, Stop, capture type, one-shot
   approval, Submit, status history, sound option. Use `Shift+Tab` to verify the
   reverse order. Labels and access keys (`Alt+T`, `Alt+E`, `Alt+C`, `Alt+S`,
   `Alt+Y`, `Alt+U`, and `Alt+I`) should identify their targets without relying
   on color.
3. Leave consent unchecked. Confirm Submit is unavailable. Connect the
   in-process mode, check the approval, submit once, and verify the textual
   sequence: submitting, accepted cue, capture off, approval consumed. Submit
   must become unavailable again.
4. Enter a malformed endpoint and activate Connect. Confirm the assertive error
   is spoken and keyboard focus returns to the control that initiated the
   operation. Repeat with external `http://` and confirm it is rejected even if
   the development checkbox is enabled.
5. Start an operation and activate Stop. Confirm cancellation/closed text is
   announced, session identifier becomes None, capture is off, and no automatic
   retry or submission follows.
6. Toggle the optional system-sound checkbox. Confirm success/error always has
   equivalent text and never relies on sound or color alone.
7. With Windows Accessibility Insights or Inspect, verify UI Automation names,
   HelpText, control types, label relationships, enabled states, and live-region
   events for session state, capture state, polite status, and assertive errors.
   Verify no custom owner-drawn control obscures the stock control semantics.
8. At 200% display scaling and with Windows high contrast enabled, verify all
   controls remain reachable by keyboard and status/error text remains visible.

These are manual acceptance checks. They must not be reported as passed until
performed on the target Windows/screen-reader combinations.

## QUICK-family validating example boundaries

These JSON records are local validating examples, not claims about an existing
QUICKGlance, QUICKSnip, or QUICKPub API. None of the utilities is required by
the relay.

QUICKGlance may provide a consented context/snapshot request:

```json
{
  "schemaVersion": "1",
  "contextId": "context-example",
  "purpose": "user-requested scene context",
  "consentGranted": true,
  "snapshotRequested": true
}
```

QUICKSnip may provide an explicitly user-selected bounded region:

```json
{
  "schemaVersion": "1",
  "selectionId": "selection-example",
  "consentGranted": true,
  "userSelected": true,
  "region": { "x": 10, "y": 20, "width": 200, "height": 100 }
}
```

QUICKPub is only a user-approved structured export consumer. The adapter creates
an inspectable envelope and performs no I/O or publication:

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

Changing any consent/selection/approval flag to `false` causes the corresponding
adapter to reject the request.

## License

SPDX-License-Identifier: MIT OR Apache-2.0
