<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Troubleshooting

Run commands from the repository root unless a section says otherwise. Use
synthetic inputs while diagnosing. Do not paste credentials, camera captures,
stable device identifiers, private paths, or full environment dumps into an
issue.

## Establish the toolchain

```bash
python3.12 --version
java -version
cmake --version
ninja --version
dotnet --info
adb devices -l
```

Only the tools for the selected target are required. Python requires 3.12;
Android requires JDK 17 and an installed SDK; native requires CMake 3.24+,
Ninja, and C++20; desktop requires .NET 8; device installation requires ADB.

## Python bootstrap fails

- `Python 3.12 is required`: install Python 3.12 and ensure `python3.12` is on
  `PATH`. Other Python minor versions are outside the declared package range.
- Dependency resolution/download fails: the lock file is authoritative; check
  network/package-index policy, then rerun `./scripts/bootstrap`. Do not weaken
  pins as a diagnostic shortcut.
- Editable imports fail: confirm `.venv/bin/python` exists and run bootstrap
  from the repository root.
- Generated protocol is stale: run `./scripts/generate`, inspect the generated
  diff, then run `.venv/bin/python -m conceptflow_mpl_protocol.validation`.

## Service does not start

- `production requires a registered non-synthetic worker implementation` is
  intentional. The included server starts only with the synthetic worker in
  development/test.
- `production profile rejects insecure binding`: set
  `MPL_INSECURE=false` and supply readable certificate and private-key paths.
- `insecure binding is restricted to loopback`: use `127.0.0.1`, `::1`, or
  `localhost`, or configure a TLS production deployment.
- `no usable CUDA device ... CPU fallback is disabled`: inspect
  `nvidia-smi`, `CUDA_VISIBLE_DEVICES`, driver/toolkit state, and
  `MPL_ALLOW_CPU_FALLBACK`. Use `MPL_DEVICE=cpu` for the synthetic CPU demo.
- gRPC bind returns zero: check whether the configured port is occupied and
  whether the process may bind the address.

Use the checked examples rather than inventing values:

```bash
.venv/bin/python scripts/repository/check_config_examples.py
set -a
. config/development.env.example
set +a
MPL_DEVICE=cpu .venv/bin/conceptflow-mpl-cluster
```

## Demo reports an invariant failure

`./scripts/demo` deliberately fails if reconnect, cancellation propagation,
timeout, worker error, stale rejection, overload, service recovery, correlation,
or assistive cue rendering violates an invariant. Rerun the matching focused
test with output:

```bash
.venv/bin/python -m pytest -q tests/test_full_slice.py -s
.venv/bin/python -m pytest -q tests/test_grpc_service.py -s
.venv/bin/python -m pytest -q tests/test_worker_pool.py -s
```

Do not increase queue sizes or deadlines until the failing state is understood;
that can conceal backpressure or cancellation defects.

## `OVERLOADED`, timeout, stale, or mismatch

- `OVERLOADED`: the bounded worker queue rejected a new request. Reduce source
  rate, apply negotiated frame dropping, add an independently validated worker,
  or revise capacity with memory/load evidence.
- `DEADLINE_EXCEEDED`: processing exceeded the smaller of the frame deadline,
  gRPC remaining time, and configured worker timeout. Profile stages before
  increasing the budget.
- stale result: inspect monotonic capture times, registration time, result age,
  and cue TTL. Do not use wall time for ordering.
- correlation mismatch: compare request, session, stream, frame, and capture
  timestamp fields. Never bypass correlation to make a cue render.

## Android build fails

- Confirm JDK 17. Unsupported Java versions may fail before compilation.
- Install Android API 36 build components and set `ANDROID_HOME` or
  `ANDROID_SDK_ROOT`; alternatively use an uncommitted `local.properties`.
- Use the wrapper: `./gradlew --no-daemon testDebugUnitTest assembleDebug`.
- If dependencies cannot resolve, check access to Google Maven and Maven
  Central. The declared baseline is Gradle 8.11.1, AGP 8.10.1, Kotlin 2.0.21.

## ADB or Rokid non-display runtime fails

- Run `adb devices -l`. If more than one device is attached, pass `-s` on every
  command.
- `unauthorized`: unlock the device and accept the host authorization prompt.
- `offline`: reconnect the cable, verify the magnetic 5-pin seating, then
  restart only the ADB client if needed.
- If the kernel reports repeated `device descriptor read/64, error -71` and the
  glasses do not appear in `lsusb`, the failure precedes udev and ADB. On the
  validated host, moving the same data cable from direct USB-C to a USB-A port
  through a data-capable adapter restored a stable 480 Mbit/s connection. Do
  not weaken device permissions or replace udev rules to mask this condition.
- Ubuntu's `android-sdk-platform-tools-common` package supplies the standard
  Android udev rules. Confirm that vendor `18d1` is covered and that the user is
  in `plugdev`; a custom Rokid rule was not required on the validated host.
- Hi Rokid's **Glasses ADB debugging** switch may be cycled and its supported
  **Restart** action used to recover device-side development state. This is an
  initial setup/recovery operation only; the sideloaded application has no Hi
  Rokid or client-secret runtime dependency.
- APK not found: build the specific module and verify its
  `build/outputs/apk/debug/` directory.
- If both the Poco and glasses are connected, pass the glasses serial to
  `./scripts/rokid-install --serial SERIAL`; the helper refuses ambiguous or
  non-Rokid targets.
- A 3-pin charging lead does not provide the required ADB data path. Use the
  magnetic 5-pin development cable.
- Camera remains stopped: explicitly install/grant with
  `./scripts/rokid-install --serial SERIAL --no-build --grant-camera`, then run
  `./scripts/rokid-control --serial SERIAL capture-start`. Inspect state with
  the helper's `status` command. Capture stops with the service.
- `CAMERA_IN_USE` or `MAX_CAMERAS_IN_USE`: another YodaOS service owns the
  camera. Record the conflict; do not disable system security or services as an
  installation shortcut.
- No phone/glasses exchange: confirm both apps retain their app-private pairing
  files, Android Node is in **Waiting for glasses** or **Capturing**, and the
  already-provisioned private WLAN is enabled on both devices. The implemented
  path is two-lane TLS 1.3 with mutual certificate pinning; ADB Wi-Fi is neither
  required nor used for runtime messages. A paired Bluetooth state alone does
  not establish this application channel, because a BLE wake/discovery adapter
  is not implemented yet.
- No Rokid client secret or SDK class is required. This project intentionally
  builds and sideloads a standalone standard-Android APK.
- Right-arm touch input is wear-gated on the tested unit. An off-head gesture
  can correctly produce no event; repeat the diagnostic while worn and keep
  the magnetic cable slack. Verified raw mappings are in
  `ROKID_INTEGRATION.md`.
- `idle-enable` briefly uses the nonvisual command Activity to establish a
  camera-capable foreground service, then the Activity exits. Camera, IMU, and
  microphone remain off while the client performs bounded mutual-TLS
  rendezvous attempts. Cooldowns are scheduled with an elapsed-realtime wakeup
  alarm, and each pre-authentication epoch has finite 17-second partial CPU and
  Wi-Fi low-latency locks covering its 15-second handshake deadline; there is
  no lock during cooldown and no `FLAG_KEEP_SCREEN_ON`. A Wi-Fi lock does not
  turn a disabled radio on. On API 31+, status reports
  `wakeup_alarm=exact_allow_idle` only when exact-alarm special access is
  available; `inexact_allow_idle` is a valid but more readily deferred fallback.
  Doze and vendor policy can still delay either route, so the Poco's 90-second
  listener is not a delivery guarantee. Reopen the listener after a missed
  window rather than weakening authentication or starting sensors in standby. After
  reboot, only inert service state is restored; run authorized `idle-enable`
  again before the Poco can request a live session.
- On the tested API-32 YodaOS build, both shell `am startservice` forms refuse
  even a resolvable exported debug service. `idle-enable` therefore launches
  the authorized Activity, which requests lock-screen visibility and screen-on
  state, waits for `onResume`, window focus, and a 150 ms settling interval,
  then starts and binds the private service. If logs do not show
  `source=authorized_visible_activity_started_foreground`, inspect the Activity
  resume/focus and service-start logs. Do not replace this with a hidden API or
  exported service. The service is private in every build variant.
- The Rokid Node's explicitly provisioned AccessibilityService observes only
  the validated PSOC key allowlist and always returns `false`; it does not
  intercept events. Worn long press remains the native YodaOS Talk-to-AI
  action. Do not add a raw input reader, hidden API, unbounded idle wake lock, or background Activity
  launch to capture it. Use the accessible Poco Start/Stop controls; retain ADB
  only as a development and recovery control.
- `idle-disable` and `stop` both mean full service shutdown and persisted idle
  disable. When a live link is active, foreground removal waits for its bounded
  authenticated close or, if its terminal callback is lost, the documented
  12-second watchdog. A persistence-write failure is logged and may allow a
  later reboot restore, but it never leaves the current capture sources running.

See [ROKID_INTEGRATION.md](ROKID_INTEGRATION.md) for the verified device and SDK
boundaries.

## .NET restore, build, or demo fails

- Confirm an 8.x SDK with `dotnet --info`.
- Restore with `--locked-mode`; unexpected lock changes need review rather than
  deletion.
- On non-Windows, the WPF project cross-compiles through
  `EnableWindowsTargeting=true` but cannot run.
- The headless demo exits 2 without consent by design. Pass
  `-- --consent-synthetic-demo` after the `dotnet run` options.
- A non-loopback HTTP origin is always rejected. HTTPS remains required even
  when the loopback development checkbox is enabled.
- The WPF default local origin is not evidence that a service is listening.
  Use deterministic in-process mode or supply an authorized TLS service origin.

## Native or CUDA build fails

- CMake older than 3.24, missing Ninja, or a compiler without C++20 support is
  outside the native prerequisites.
- Strict warning failures are build failures; correct the warning rather than
  disabling `-Werror` or `/WX` for release validation.
- With `CONCEPTFLOW_ENABLE_CUDA=ON`, CMake must find both a CUDA compiler and the
  toolkit. Check `nvidia-smi`, `nvcc --version`, host-compiler compatibility,
  and `CUDAToolkit_ROOT` if the toolkit is installed outside normal discovery.
- A successful CUDA-aware build still does not run a kernel. Absence of GPU
  utilization during the native demo is expected.
- Sanitizer failures require the exact compiler, flags, stderr, and failing test
  case. Some platforms need different sanitizer runtime configuration.

## Accessibility behavior differs

- Android app speech may be intentionally suppressed while TalkBack or another
  accessibility service is enabled. Verify the live-region text instead of
  forcing duplicate TTS.
- WPF system sound is optional; state and error text must remain complete with
  it disabled.
- Missing or repeated announcements, unreachable Stop/Cancel, lost focus,
  clipped large text, or cues that overwhelm a screen reader are release
  blockers. Follow [ACCESSIBILITY.md](ACCESSIBILITY.md) and record the exact
  device/screen-reader/version combination.

## Repository checks fail

```bash
.venv/bin/python scripts/repository/check_policy.py
.venv/bin/python scripts/repository/secret_scan.py
.venv/bin/python scripts/repository/check_config_examples.py
git diff --check
```

Policy failures commonly indicate a missing SPDX header, generated/build
artifact, absolute user path, credential-shaped example, or unsafe config.
Fix the source; do not exclude a real finding.

For private security issues use [`SECURITY.md`](../SECURITY.md). For expected
behavior and evidence boundaries, read [`VALIDATION.md`](../VALIDATION.md).
