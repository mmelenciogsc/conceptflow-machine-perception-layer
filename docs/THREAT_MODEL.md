<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Threat model

This threat model covers the public reference implementation and the seams at
which private device, transport, model, or deployment code may be added. It is
an engineering review aid, not a security certification.

## Protected assets

- camera frames, microphone audio, derived scene text, pose, and spatial metadata;
- user consent and capture state;
- session, request, result, stream, and frame integrity;
- cue ordering, urgency, modality, and accessible presentation;
- TLS keys, signing material, and optional model-provider credentials;
- worker health, capacity, model selection, and provenance;
- CI artifacts, release packages, dependency integrity, and brand assets.

## Trust boundaries

| Boundary | Untrusted or failure-prone input | Primary controls | Remaining work |
| --- | --- | --- | --- |
| Glasses | Camera/sensor data, permissions, YodaOS service contention, local cue output | Android permission gate, bounded single-owner lease, short explicit microphone sub-lease, latest-wins outbox, monotonic IDs, result correlation, TTL/dedup renderer, direct-ADB target check | Sustained lifecycle, authenticated production transport, localization, and BVI acceptance |
| Android host | Frames, network state, transport callbacks, accessibility services | Lease/session-bound ingress, bounded camera assembly, digest/order checks, latest-unread frame, capability detection, preprocessing, bounded queue, session/correlation/scheduler policy, TLS client | Real phone-to-glasses transport and end-to-end device testing |
| Windows relay | Endpoint text, user approval, future screen/region content, status UI | Consent gate, content bounds, HTTPS policy, cancellation, bounded queues, redaction, stock accessible controls | Manual Windows, JAWS, and NVDA acceptance; real capture adapters |
| Network | Eavesdropping, tampering, replay, downgrade, delay, reordering, flooding | TLS-required production config, loopback-only plaintext, ephemeral sessions, correlation, deadlines, message limits | Deployment authentication, certificate lifecycle, optional mTLS, WebRTC security design |
| CUDA/backend | Malformed frames, exhausted queues, unhealthy workers, malicious model output, GPU failure | Preprocessing, admission bounds, timeout/cancellation, health thresholds, provenance, mock-only default | Sandboxed real workers, kernel/model validation, resource isolation |
| CI/supply chain | Dependency compromise, secret exposure, untrusted artifacts, overprivileged workflows | Pinned versions/locks, read-only workflow permission, policy and secret scans, no committed build outputs | Release signing, provenance/SBOM policy, protected runner operations |
| Optional model providers | Content disclosure, policy drift, retention, prompt/model manipulation | No provider implementation in baseline; local/privacy route can fail closed | Explicit provider contract, consent, minimization, DPA/retention and egress controls |

## Principal abuse cases

### Unintended or covert capture

An application or future transport integration could start capture without
the user understanding the state, continue after disconnect, or broaden a
selected region. The non-display glasses client remains stopped after install;
development capture requires an explicit authorized-ADB action and stops with
the service. The Android host and Windows samples require explicit actions and
validate bounded input. Future zero-touch operation must retain accessible host
status, distinctive nonvisual glasses feedback, and an immediate stop control.

### Frame disclosure

Frame bytes could leak through plaintext transport, logs, crash reports,
telemetry, temporary files, model providers, or issue attachments. Production
Python and Android configurations reject plaintext. The Rokid debug build
permits it only for literal loopback through an authorized ADB reverse tunnel;
this is not production peer authentication. Logs redact payload fields and no
retention store exists. Deployments must also secure certificates, proxies,
swap/crash collection, GPU tools, and third-party providers.

### Lease spoofing or replay

A network peer could claim another peer's lease ID, replay sensor packets, or
silently renew microphone collection. Protobuf identifiers are correlation
values, not credentials. The current controller accepts only an identity
supplied by an authenticated transport boundary, permits one owner, enforces
monotonic local expiry, and refuses owner-mismatched renewal/close. Renewal does
not extend microphone consent. The host ingress rejects wrong lease/session
identity and non-increasing envelope, frame, batch, and sample order. The
wireless adapter must still implement authenticated pairing, encryption, key
rotation, replay-resistant connection setup, revocation, and clear nonvisual
capture state before this boundary is production-ready.

### Cue spoofing or mis-correlation

An attacker or faulty worker could attach a plausible cue to the wrong frame or
replay an old result. The protocol repeats the correlation tuple, and host
correlators reject unknown, mismatched, stale, duplicate, cancelled, and
out-of-order results. TLS channel protection and endpoint authorization remain
necessary; application correlation is not peer authentication.

### Resource exhaustion

Oversized messages, rapid frames, slow workers, reconnect storms, or excessive
cues could consume memory, GPU time, audio focus, or attention. Current controls
include byte/dimension limits, bounded queues, explicit overload, deadlines,
failure thresholds, bounded retries, cue capacity, TTL, deduplication, and
priority. Production ingress still needs rate limiting per authenticated client.

### Malicious or unsafe model output

A model can be wrong, manipulated, overconfident, or intentionally harmful.
Correlation and provenance establish origin and freshness, not truth. Rendered
cues are marked assistive-only; no output may control safety-critical behavior.
Real models require evaluation, output constraints, confidence policy, human
factors testing, rollback, and monitoring before deployment.

### Accessibility denial or overload

Rapid, speech-only, sound-only, color-only, stale, or focus-disrupting feedback
can make the product unusable or hazardous. Schedulers bound and deduplicate
cues, WPF and Android expose text/state semantics, and the Android host avoids
duplicating TTS when an accessibility service is enabled. TalkBack, keyboard,
JAWS, and NVDA acceptance remains release-blocking and is not yet validated on
the target combinations.

### Wrong-device ADB mutation

The Poco and Rokid glasses can be attached to one workstation simultaneously.
An unqualified `adb install`, permission grant, or shell command could mutate
the wrong device. The direct-sideload helper requires an explicit serial when
more than one authorized device exists and refuses targets whose Android product
properties do not identify as Rokid glasses. Manual procedures must use `-s` on
every mutating ADB command.

### Supply-chain or CI compromise

Build systems fetch Python, Gradle, NuGet, and platform dependencies. The repo
pins versions and uses package lock files where available, but a lock file is
not proof of benign code. Protect branches and self-hosted runners, restrict
workflow tokens, review dependency changes, scan produced artifacts, and keep
signing keys outside the repository and CI logs.

## Security invariants

- Production service transport is TLS-only; insecure transport is loopback-only
  and explicitly developmental.
- No raw frame or secret enters normal structured logs or status text.
- Capture and export are disabled until an applicable user action grants them.
- Queues, frames, messages, dimensions, deadlines, retries, and histories are
  bounded.
- Cancellation crosses the host/transport/worker boundary where implemented.
- Results are never rendered without correlation and freshness checks.
- Worker/model provenance and synthetic status remain visible.
- Accessibility is a correctness requirement, not a discretionary enhancement.
- Proprietary SDKs, keys, captures, weights, and private brand media stay out of
  the public repository.

## Verification priorities before production

1. Add authenticated authorization and certificate-rotation procedures.
2. Validate the complete physical device transport, including disconnect,
   reconnection, cancellation, rate limiting, and capture-stop behavior.
3. Perform independent security review and fuzz protocol/content boundaries.
4. Exercise real model workers in isolated resource limits and evaluate unsafe
   output behavior.
5. Complete the accessibility matrix in [ACCESSIBILITY.md](ACCESSIBILITY.md).
6. Measure physical end-to-end latency and overload behavior using
   [LATENCY_BENCHMARKING.md](LATENCY_BENCHMARKING.md).
7. Add release provenance, artifact signing, dependency review, and incident
   response appropriate to the deployment.

Report suspected vulnerabilities privately as described in
[`SECURITY.md`](../SECURITY.md). Never include live camera data, credentials, or
personal identifiers in a public report.
