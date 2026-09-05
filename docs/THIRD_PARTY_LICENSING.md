<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Third-party licensing for perception integrations

The repository's own code remains `MIT OR Apache-2.0`. That expression does not
relicense tools, plug-ins, model code, checkpoints, fonts, brands, or datasets.

| Dependency/artifact | Current repository treatment | Verification needed before distribution/use |
| --- | --- | --- |
| FMOD Studio / FMOD Unity | No binaries committed; generated WAV/banks ignored; project metadata and original authoring scripts only | Developer/end-user FMOD license and redistribution terms |
| Resonance Audio FMOD plug-in | Referenced by locally authored FMOD metadata; no binary copied by this change | Terms supplied with the installed FMOD integration |
| Ultralytics / YOLOE-26S | External fixed-vocabulary model and export tool; no package, weight, ONNX, or QNN library committed | AGPL-3.0 obligations or documented commercial authorization before distribution or network-service use; checkpoint terms |
| Depth Anything V2 indoor Large | External model ID only; no package or weight | Model card currently exposes no license metadata in the checked API response; obtain explicit terms |
| Depth Anything V2 outdoor Large | External model ID only; no package or weight | Checked model card reports Apache-2.0; pin revision and retain notices |
| Depth Anything V2 Hypersim Small | External checkpoint and generated model library only; none committed | Official code and checked model card declare Apache-2.0; pin revision/checksum and retain notices |
| Depth Anything V2 VKITTI Small | External checkpoint and generated model library only; none committed | Official code and checked model card declare Apache-2.0; pin revision/checksum and retain notices |
| Qualcomm AI Engine Direct / QNN (QAIRT 2.48) | Opt-in JNI code compiles against an external SDK; proprietary headers, samples, tools, runtime libraries, and DSP skel are not copied or committed | Accept Qualcomm terms and confirm runtime redistribution rights; keep proprietary material outside the permissive source tree and provision privately |
| Qualcomm AI Hub Depth Anything V2 release artifacts | External evaluation only; no DLC, ONNX graph, package, or weight committed | Verify AI Hub artifact/model terms for the exact release before redistribution; upstream Small licensing does not automatically grant rights to every compiled vendor artifact |
| whisper.cpp | Source remains external and is pinned to commit `eacbd8234c6654cdbf2c377f72b2106875479bdc`; no source or binary is committed | MIT license; retain upstream copyright/license notice when distributing a linked binary |
| Whisper `small.en-q5_1` converted weight | External app-private artifact; hash-pinned but not committed; checked `ggerganov/whisper.cpp` revision `5359861c739e955e79d9a303bcbc70fb988958b1` declares MIT and upstream OpenAI Whisper declares MIT | Retain both applicable MIT notices and recheck the exact artifact revision before redistribution |
| Silero VAD v6.2.0 GGML artifact | External app-private artifact; hash-pinned but not committed; checked `ggml-org/whisper-vad` revision `9ffd54a1e1ee413ddf265af9913beaf518d1639b` declares MIT | Retain the repository's MIT notice and recheck the exact artifact revision before redistribution |
| Unity | Project source/settings only; no editor/runtime redistribution in Git | Unity editor/runtime license for target distribution |
| Rokid/Android/NVIDIA SDKs | Platform boundary only; no proprietary SDK or driver binaries committed | Vendor terms for each deployed component |

The Android indoor and outdoor Depth Anything model IDs are separate by design;
neither is silently downloaded. The exact checked revisions observed on
2026-08-22 were `3bc65d4e14a6786a61acec16453c50e12bf5f338`
(Hypersim Small) and `c725b8589bdf6ab04072cab74c0467830db80d6d`
(VKITTI Small). Their official checkpoint SHA-256 values are recorded in the
external model-preparation tool. These observations are not an immutable
license guarantee.

Before enabling an external adapter, record package and weight versions,
artifact hashes, license texts, distribution/service implications, attribution,
and approval. Never place model weights or proprietary plug-ins in Git history.
