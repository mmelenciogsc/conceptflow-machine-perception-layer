<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Third-party licensing for perception integrations

The repository's own code remains `MIT OR Apache-2.0`. That expression does not
relicense tools, plug-ins, model code, checkpoints, fonts, brands, or datasets.

| Dependency/artifact | Current repository treatment | Verification needed before distribution/use |
| --- | --- | --- |
| FMOD Studio / FMOD Unity | No binaries committed; generated WAV/banks ignored; project metadata and original authoring scripts only | Developer/end-user FMOD license and redistribution terms |
| Resonance Audio FMOD plug-in | Referenced by locally authored FMOD metadata; no binary copied by this change | Terms supplied with the installed FMOD integration |
| Ultralytics / YOLOE-26S | Model-neutral boundary only; no package or weight | AGPL-3.0 obligations or applicable enterprise authorization; checkpoint terms |
| Depth Anything V2 indoor Large | External model ID only; no package or weight | Model card currently exposes no license metadata in the checked API response; obtain explicit terms |
| Depth Anything V2 outdoor Large | External model ID only; no package or weight | Checked model card reports Apache-2.0; pin revision and retain notices |
| Unity | Project source/settings only; no editor/runtime redistribution in Git | Unity editor/runtime license for target distribution |
| Rokid/Android/NVIDIA SDKs | Platform boundary only; no proprietary SDK or driver binaries committed | Vendor terms for each deployed component |

The indoor and outdoor Depth Anything model IDs are separate by design; neither
is silently downloaded. The known revisions observed on 2026-08-22 were
`d2fc6a93601aabb1139a3bf0ebfcb4e89c67817f` (indoor) and
`4eab4cf1983c2801c515804005214de56a4b67cc` (outdoor). These observations are
not an immutable license guarantee.

Before enabling an external adapter, record package and weight versions,
artifact hashes, license texts, distribution/service implications, attribution,
and approval. Never place model weights or proprietary plug-ins in Git history.
