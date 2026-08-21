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
