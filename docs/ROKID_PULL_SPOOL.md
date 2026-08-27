<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Rokid diagnostic pull spool

Rokid Node retains a bounded pull spool only for explicit regression and A/B
diagnostics. It is disabled by default and is not the production Machine Vision
input path. Production uses the bounded in-memory publisher defined in
[`STREAMING_PROTOCOL.md`](STREAMING_PROTOCOL.md).

When the diagnostic flag is enabled for the next session, the existing camera
darkness, blur, cadence and frame-similarity gates and the existing IMU
significance/duplicate gate run first. Only admitted data reaches the spool.

## Private on-glasses layout

The runtime root is Android app-private no-backup storage:

```text
noBackupFilesDir/machine-perception-spool/
├── camera/
│   └── camera-<frame-id>-<capture-ns>.jpg
├── microphone/
│   └── microphone-<chunk-id>-<capture-ns>.wav
├── manifest.json
└── .manifest-state.pb
```

Neither application, ADB tooling, nor documentation depends on the absolute
device path. `manifest.json` contains the timestamp, kind, relative artifact
path, byte count, SHA-256 digest, and media metadata for camera/microphone
records. Selected IMU batches are inline JSON, including their sample sequence,
HEAD-frame quaternion, angular velocity, linear acceleration, accuracy, and
individual monotonic timestamps. The private protobuf sidecar is only a
crash-recovery index; Android Node polls and verifies the canonical JSON
projection, not the sidecar.

## Camera transform

The current source hands the optional spool an already gate-admitted 640×640
packed RGB8 frame with row stride 1920. The spool verifies its exact dimensions,
stride, byte count, and digest before bitmap allocation, encodes it directly to
bounded JPEG without rescaling or cropping, updates the descriptor and digest,
and retains its already-transformed pinhole intrinsics.

The separately retained legacy diagnostic JPEG input still uses deterministic
aspect-fill scaling and a centered 640×640 crop, with matching intrinsics
translation. That compatibility branch is not the production camera path. No
branch stretches, letterboxes, or pads the image.

## Poll, fetch, acknowledge

During a mutually authenticated live lease, Android Node polls at a nominal
10 Hz over the existing TLS realtime/control lane. A response is limited to 16
oldest records and 60 KiB of canonical JSON. IMU batches are inline. For a
camera or WAV record, Android requests sequential 48 KiB chunks, verifies the
declared byte count and SHA-256 digest, recreates the existing typed sensor
envelope, then acknowledges the record. Rokid deletes acknowledged files and
manifest records. A missing response is retried after one second; malformed
paths, hashes, sizes, ordering, or record kinds fail closed.

Rokid never exposes its private filesystem to the phone. The phone polls the
JSON manifest through this authenticated application protocol because Android
sandboxing correctly prevents one device from opening another device's private
path directly.

The spool is limited to 512 records and 64 MiB of artifacts. Oldest records are
evicted if a disconnected host cannot keep up. A new authenticated session
clears data from the preceding session to prevent stale monotonic timestamps or
reset frame IDs from being treated as current observations. IMU-driven manifest
durability is coalesced to at most 10 writes per second; an Android poll and
camera/microphone artifact force an atomic update. This is bounded temporary
retention, not a capture archive.

Microphone files exist only for PCM admitted during a separately authorized,
maximum-ten-second on-demand microphone sublease. They are standard PCM16 WAV
chunks. Stopping or expiration still closes `AudioRecord`; it does not authorize
new files.

## Physical validation and limitation

JVM tests cover exact crop geometry, canonical JSON, page hashing, inline IMU
delivery, chunked artifact reconstruction, SHA-256 rejection, acknowledgement,
and the controller's spool-instead-of-push routing. APK compilation proves the
Android bitmap, atomic-file, and WAV implementation type-checks.

A 30-second hardware run on 2026-08-25 confirmed the route but also demonstrated
why it is diagnostic-only: 80 camera and 692 IMU records were produced;
6,843,275 artifact bytes, 35,406,382 JSON-manifest bytes and 46,751,870
recovery-state bytes were written. The host pulled 793 poses, of which 717 were
already stale, no camera completed before shutdown, and 47 camera plus 302 IMU
records remained backlogged. A pre-session race that could expose a previous
session's recovery records was found and repaired; manifest/artifact access now
requires an active session. Captured artifacts from the test were removed from
the glasses after aggregate evidence was recorded.
