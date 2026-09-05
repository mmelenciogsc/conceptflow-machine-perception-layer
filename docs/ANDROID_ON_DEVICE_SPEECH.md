<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Android on-device speech window

Machine Perception Layer, Android Node owns a bounded local speech pipeline for
the Rokid microphone stream. It is not an always-listening recorder. The
quantized English Whisper model and Silero voice-activity detector are loaded
once while Android Node is active, kept ready in native memory, and released
when the node stops.

## Runtime contract

The existing Rokid microphone permission, user-intent gate, ten-second lease,
PCM16 capture, authenticated transport, source timestamps, and queue policy are
unchanged. Android Node now drains each accepted `TimedAudioBlock` immediately
from `SensorTimeline` into one fixed-capacity RAM window:

```text
Rokid mic gate -> authenticated PCM16 blocks -> SensorTimeline (16 blocks)
  -> immediate drain -> 10-second RAM window -> mono 16 kHz conversion
  -> Silero VAD -> optional Whisper small.en transcription
```

The RAM window accepts mono or stereo PCM16 at 8–48 kHz and has an absolute
1,920,000-byte bound (ten seconds of 48-kHz stereo PCM16). It rejects format
changes, malformed samples, samples outside the monotonic ten-second window,
and capacity overflow. Source timestamps are preserved. The speech window's
owned mutable PCM and float buffers are zeroed when it finishes or is cancelled;
immutable protobuf transport objects are released for garbage collection after
the immediate drain.

Two purposes are distinct:

- `AMBIENT_AND_VAD` follows a newly accepted indoor/outdoor VLM classification.
  It updates the content-free ambient profile and runs VAD, but never releases
  transcript content.
- `USER_QUERY` follows an explicit microphone request, including entry into the
  focused-object VQA operation. It runs VAD and invokes Whisper only when
  Silero detects at least 250 ms of speech.

The current focused-object VQA request still uses its existing conservative
default question. The private speech result is an ephemeral callback contract
for the next conversational-VQA step; it is not yet substituted into that
request, retained, displayed, logged, or sent over the network. A new window is
not admitted while prewarm or analysis is in progress, and cancellation or a
session change invalidates an in-flight result before it can be published.

Known application playback can suppress blocks through an explicit monotonic
interval. The on-glasses branded sequence uses this path. This is not acoustic
echo cancellation: Android cannot provide a reliable reference for TalkBack
or remote open-ear playback captured by the glasses microphone. Physical
testing must therefore tune timing and VAD thresholds without claiming that
all echo is removed.

## Pinned external runtime

The implementation is built against upstream `ggml-org/whisper.cpp` commit
`eacbd8234c6654cdbf2c377f72b2106875479bdc`. The initial target profile is:

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `ggml-small.en-q5_1.bin` | 190,098,681 bytes | `bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30` |
| `ggml-silero-v6.2.0.bin` | 885,098 bytes | `2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987` |

Neither artifact nor the whisper.cpp source tree is committed. The runtime
verifies size, GGML magic, and SHA-256 before loading. Provisioning repeats the
digest check after the atomic transfer into mode-0600 app-private storage.

The arm64 baseline uses NEON with ARMv8.2 FP16 vector arithmetic and DOTPROD.
The Poco F7 Ultra reported those features as well as i8mm, but i8mm is not
enabled in the baseline because doing so raises the binary's architecture floor
and needs a measured benefit. Four inference threads are selected on the Poco;
the policy remains bounded to two through four based on available processors.
Whisper remains on CPU so speech cannot monopolize the HTP scheduler used by
YOLOE, Depth Anything, and the local VLM.

Build the JNI-enabled APK from the pinned external source:

```bash
scripts/android-whisper-build --android-sdk "$ANDROID_SDK_ROOT"
```

Or provide an already checked-out exact source tree:

```bash
scripts/android-whisper-build \
  --source /path/to/whisper.cpp \
  --android-sdk "$ANDROID_SDK_ROOT"
```

For the full Android Node perception build, include the externally installed
QAIRT SDK so one APK contains both project JNI boundaries:

```bash
scripts/android-whisper-build \
  --source /path/to/whisper.cpp \
  --android-sdk "$ANDROID_SDK_ROOT" \
  --qnn-sdk /private/path/to/qairt
```

That produces
`apps/android-host/build/whisper/android-host-qnn-whisper-debug.apk` and fails
unless both `libconceptflow_whisper_jni.so` and `libconceptflow_qnn_jni.so` are
present. QNN and model binaries remain externally provisioned.

Install the emitted APK (`android-host-qnn-whisper-debug.apk` for the combined
command above, otherwise `android-host-whisper-debug.apk`), then provision
separately acquired official artifacts:

```bash
scripts/android-whisper-provision \
  --serial "$POCO_SERIAL" \
  --model /private/path/ggml-small.en-q5_1.bin \
  --vad-model /private/path/ggml-silero-v6.2.0.bin
```

Restart Android Node. Its accessible status reports prewarm/readiness, speech
detection, transcript character count, analysis duration, accepted/rejected
blocks, playback suppression, and the independent sensor-timeline overflow
counter. It never reports transcript or audio content.

## Validation boundary

On 2026-09-01 the JNI-enabled speech build compiled for arm64 and loaded both
private artifacts on the attached Poco F7 Ultra. The exact final combined build
reached `ready` 2,748 ms after a host-initiated cold app start; this observation
includes launch and status-polling overhead. Four explicit live Rokid microphone windows delivered
77, 77, 77, and 55 chunks, and a forced-indoor ambient/VAD window delivered 75;
all five had zero Android timeline overflow and zero rejected speech blocks.
No-speech VAD analysis took 245–328 ms. The ambient run produced a content-free
profile with a -61 dBFS relative noise floor.

The final combined QNN-plus-Whisper APK was then installed. Its automatic QNN
environment decision selected indoor and started the intended ambient/VAD
window while camera/IMU streaming and QNN HTP inference continued. That window
delivered 77 blocks / 315,392 bytes with zero overflow or rejection and Silero
completed in 385.0 ms on the final clean build. A private-files inspection found no PCM, WAV,
compressed-audio, image, or JSON capture artifact. This proves combined model
loading, live PCM transport, bounded consumption, ambient feature extraction,
and no-speech VAD behavior.

On 2026-09-02 an untethered, worn positive-speech trial used the nonvisual
debug control without opening an Activity or moving TalkBack focus. The command
returned in 1,074 ms, Rokid Node delivered 76 blocks / 311,296 bytes, Silero
reported speech, and Whisper produced a 47-character private result in 6,949.4
ms without timing out. Camera and IMU delivery continued during the window.
Only the character count and aggregate timing were inspected; transcript
content was not logged or copied into the validation record. This proves the
positive VAD-to-Whisper path on the attached devices, but not recognition
accuracy, open-ear echo robustness, or long-duration thermal stability.

## Upstream evidence

- whisper.cpp repository and Android example: <https://github.com/ggml-org/whisper.cpp>
- pinned source commit: <https://github.com/ggml-org/whisper.cpp/commit/eacbd8234c6654cdbf2c377f72b2106875479bdc>
- official converted model repository: <https://huggingface.co/ggerganov/whisper.cpp>
- official whisper.cpp VAD repository: <https://huggingface.co/ggml-org/whisper-vad>

URLs and artifact metadata were checked on 2026-09-01.
