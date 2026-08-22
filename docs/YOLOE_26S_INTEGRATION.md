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
is vendored or downloaded by repository scripts. The preparation command
requires a caller-supplied checkpoint and an explicit acknowledgement of the
separately governed terms. The public vertical slice uses deterministic
permissive fixtures.

The Android Node uses the closed `BviClassCatalog` vocabulary. The implemented
export calls `set_classes` with the exact ordered 40 prompts before producing a
static `1×3×640×640` ONNX graph, then verifies ONNX metadata against vocabulary
SHA-256 `2ca8ebc9d1b7914e1dfd1d288e517e78e1b24be75ad04cd6bc0df3e0455aca44`.
The accepted baseline QNN library uses FP16 on HTP; quantization is not treated
as a prerequisite. Runtime prompts and YOLOE's
prompt-free/open-knowledge class set are out of scope. A locally available
legacy ONNX export was inspected as static `1x3x640x640`, but its embedded
330-class custom vocabulary does not match the Android Node list and the app
therefore rejects it rather than silently using broader knowledge.

On the attached Poco F7 Ultra, QAIRT 2.48.40/QNN HTP V79 executed the FP16
graph 25 times with an 80.065 ms median client inference time. A higher-signal
COCO calibration image produced 31/31 class-aware IoU≥0.5 matches above 0.05
confidence against ONNX Runtime, with mean matched IoU 0.9886. This is a
single-image conversion-fidelity smoke test, not detection/segmentation
accuracy evidence. Plain W8A8 was rejected because shared quantization of the
mixed detection output destroyed confidence/class semantics. W8A16 retained
those semantics but remains experimental pending representative BVI accuracy
testing and showed no material YOLO latency improvement.

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
