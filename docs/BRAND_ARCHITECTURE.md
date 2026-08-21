<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Brand architecture

This document records the verified external brand sources and the visual system
observed from them. It does not grant redistribution rights. No external banner,
logo, avatar, or private brand media has been copied into this repository
because redistribution status has not been established.

## Canonical source record

Paths below are relative to the inspected source collection, never to a user’s
machine.

| Role | Source-relative filename | SHA-256 | Decision |
| --- | --- | --- | --- |
| Canonical no-logo banner | `CONCEPTFlow_banner_work_20260817_001806/final/CONCEPTFlow_Banner_Master_NoLogos_2560x900.png` | `37d1eb260d1e9f60ee141e9484d68c27405e2c87a902a20c2b15b21ea38f4fe4` | Primary visual reference without third-party marks. |
| Canonical with-logos banner | `CONCEPTFlow_banner_work_20260817_001806/final/CONCEPTFlow_Banner_BespokeB2B_2560x900.png` | `513e3d7aa95b6b1d407981c3d60322a8a52bb440548eb041290e35fa70471a50` | Reference for the bespoke partner composition; do not redistribute without rights review. |
| Strongest avatar candidate | `CONCEPTFlow_banner_work_20260817_001806/raw/conceptflow-master-logo.svg` | `5d46c42fe6375a0a57150505dd5b8616b7278a9d83e263088a71ed1f8b47ad15` | Best observed scalable avatar candidate; no separate final avatar was found. |

Hashes identify the exact inspected files. They do not prove authorship,
licensing, trademark clearance, approval for publication, or suitability for a
particular derivative.

## Visual language

The canonical references use a restrained technical system:

- near-black charcoal field;
- dark blue-gray panels;
- thin cool-gray or white borders;
- muted yellow accent, approximately `#e6dc5a`;
- slight purple and yellow glows rather than saturated gradients;
- strong grid, alignment, and negative space;
- rounded outer corners around 30 px, inner containers around 24 px, and
  modules around 16 px at the 2560 × 900 source scale;
- primary text approximately `#f4f7fb`;
- tagline approximately `#d8dee8`;
- descriptor text approximately `#b1bac5`; and
- panel fields approximately `#0d1118` and `#090b0f`.

These values are visual measurements and practical starting points, not a
formal token export. Check contrast in the final medium; muted text and glow
effects must not reduce readability.

## Typography

The source can be verified as using Noto Sans regular/bold with a Liberation
Sans Bold fallback. Do not infer a proprietary typeface or claim a more exact
font identity from appearance alone. Use platform fallbacks that preserve
legibility, weight distinction, and international character coverage.

For repository documentation and accessible application UI, typography should
remain functional: clear hierarchy, ordinary sentence case, generous line
height, no condensed body copy, and no information encoded only by font weight.

## Verbal architecture

The release hierarchy is:

1. **CONCEPTFlow: Machine Intelligence. Human Architecture.** — repository and
   system heading.
2. **Machine Perception Layer — It’s just supplemental awareness.** — product
   layer and safety-positioning line.
3. Component names — Rokid client, Android host, Windows relay, CUDA cluster,
   protocol, and integrations.

“Supplemental awareness” must remain literal. Avoid wording that implies
perfect perception, autonomous navigation, hazard detection guarantees, medical
advice, emergency response, zero physical latency, or replacement of a mobility
aid or human judgment.

Use “near-real-time” only with a named measured path and evidence. Use
“zero-touch” only for an intentional operating mode with accessible start/stop
and consent; it does not mean zero latency or no user control.

## Layout guidance

Release surfaces should favor one strong statement, one clear descriptor, and
a disciplined module grid. Keep decorative glows behind content, preserve
negative space, and use border/radius hierarchy consistently. Do not place
critical status in a decorative hero treatment.

Product UI must follow platform accessibility semantics before brand styling.
Maintain contrast, visible focus, text alternatives, scalable text, minimum
touch targets, and a text channel for every sound/haptic state. A brand-perfect
surface that fails [ACCESSIBILITY.md](ACCESSIBILITY.md) is not release-ready.

## Asset governance

Before adding or publishing an asset:

1. Match the source-relative filename and SHA-256 to the record above.
2. Establish copyright, trademark, partner-logo, model-release, and
   redistribution status.
3. Prefer the no-logo master unless partner marks are specifically authorized.
4. Confirm each third-party logo has current placement and co-brand approval.
5. Produce documented exports from the canonical source; do not repeatedly
   resample prior exports.
6. Check dimensions, color profile, alpha, safe area, contrast, responsive crop,
   and legibility.
7. Record derivative filename, dimensions, tool/version, source hash, and
   approval owner.
8. Keep private sources and unapproved derivatives out of the public repository.

If an avatar is needed, treat the SVG above as a candidate pending rights and
visual approval. Do not describe it as a final avatar until a separately
approved final asset exists.

## Repository state

No proprietary model weights, third-party SDK binaries, camera captures, or private brand media
are included. Documentation records only source-relative evidence, hashes, and
design rationale. The code license (`MIT OR Apache-2.0`) does not automatically
license external trademarks or media; see
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).
