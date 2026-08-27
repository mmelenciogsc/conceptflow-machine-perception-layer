<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Public FMOD authoring contract

`audio_contract.json` is the machine-readable contract for the focused-object
and nonvisual-interface events. `AuthoringTools/generate_audio.py` creates all
WAV inputs deterministically; those WAV files and built banks are ignored and
must not be committed.

Focused-object speech ducking is owned solely by the authored
`DwellSpeechActive` volume curve. The runtime always supplies the current
parameter state, including when it creates an icon during active speech, and
does not apply a second volume multiplier.

`author_project.js` creates the complete four-event project from the official
FMOD Studio 2.03.14 Examples template. `upgrade_focused_contract.js` is the
one-way migration for the earlier two-event lab project. Both scripts fail
closed if their expected starting state is absent. Validate the generated
contract and authoring project with:

```bash
python3 FMOD/AuthoringTools/validate_audio_contract.py
python3 FMOD/AuthoringTools/generate_audio.py --output FMOD/Assets
QT_QPA_PLATFORM=minimal /opt/fmodstudio/fmodstudiocl \
  -script FMOD/AuthoringTools/validate_project.js FMOD/ConceptFlowMPL.fspro
```

The Unity runtime compiles and tests with `InspectableFmodBackend` without any
proprietary dependency. `FmodStudioPerceptionAudioBackend.cs` is intentionally
guarded by `CONCEPTFLOW_FMOD_UNITY`; enabling it also requires installing the
licensed FMOD Unity package and adding its assembly reference in the consuming
project. No FMOD Unity binaries or generated banks belong in this repository.
