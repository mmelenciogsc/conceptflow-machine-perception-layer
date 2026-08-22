<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# YOLOE-26S integration boundary

Ultralytics' current YOLOE documentation, checked 2026-08-22, names the small
segmentation checkpoint `yoloe-26s-seg.pt`. The repository does not use the
invented name “YOLOE-v26s”.

The implementation depends only on the model-neutral `SemanticSegmenter` /
`OpenVocabularySegmenter` protocol. Its output must be converted to
`SegmentedObservation`: stable track ID, prompt/class label, confidence,
timestamp, reliable mask-depth samples, and optional velocity. The core then
performs depth fusion, body-overlap calculation, similarity gating, and icon
selection independently of Ultralytics.

No Ultralytics Python package, source, model weight, generated engine, or sample
is vendored or downloaded by repository scripts. The public vertical slice uses
deterministic permissive fixtures.

The Android Node uses the closed `BviClassCatalog` vocabulary. An acceptable
export must call the upstream static-class mechanism before export, use a
quantized static input shape suitable for QNN HTP, and provide both artifact
SHA-256 and the exact baked-vocabulary SHA-256. Runtime prompts and YOLOE's
prompt-free/open-knowledge class set are out of scope. A locally available
legacy ONNX export was inspected as static `1x3x640x640`, but its embedded
330-class custom vocabulary does not match the Android Node list and the app
therefore rejects it rather than silently using broader knowledge.

## Licensing decision

The inspected upstream `ultralytics` project declares AGPL-3.0 and Ultralytics
also offers separate enterprise licensing. AGPL-3.0 is not the same as, and is
not covered by, this repository's `MIT OR Apache-2.0` choice. Therefore YOLOE is
an external, separately governed integration only. A distributor or service
operator must obtain legal review and satisfy the applicable upstream/model
terms before enabling it. No conclusion here is legal advice.

An eventual adapter must require an explicit external endpoint or separately
installed environment, a pinned version/checksum, bounded input/output, timeout
and cancellation, redacted logs, and provenance identifying the model stage.
Failure must remove semantic richness without blocking Tier 0 geometry.
