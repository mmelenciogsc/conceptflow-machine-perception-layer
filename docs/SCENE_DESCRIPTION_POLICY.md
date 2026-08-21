<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Scene description policy

Scene descriptions are Tier 2 output and never sit in the immediate geometry
loop. The request contains a bounded, structured summary of at most eight
nearest reliable tracks, coarse 15-degree azimuths, and near/mid/far distance
bands. It does not expose raw frames or fabricate exact distances.

On-demand requests always bypass scene-similarity suppression and receive high
semantic priority. Optional periodic requests require both a changed structured
fingerprint and the configured cooldown (15 seconds in the reference). An empty
map explicitly says no reliable structured objects are available and instructs
the describer not to infer a clear or safe scene.

The VLM/service adapter is independent of the geometry update. A near-body or
rapid-approach cue can duck or interrupt lower-priority speech while leaving the
anchor and haptic channel active. Stable paraphrases are avoided by fingerprinting
the structured scene supplied to the describer, not by storing raw imagery.

Descriptions are orientation context, not route clearance, crossing advice, or
a safety determination.
