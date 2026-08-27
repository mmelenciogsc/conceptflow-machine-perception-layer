#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Fail-closed structural validation for the public FMOD/Unity contract."""

from __future__ import annotations

import json
from pathlib import Path
import sys


def main() -> int:
    contract_path = Path(__file__).resolve().parents[1] / "audio_contract.json"
    value = json.loads(contract_path.read_text(encoding="utf-8"))
    assert value["schema"] == "conceptflow.mpl.audio-contract.v2"
    focused = value["events"]["focusedObject"]
    interface = value["events"]["interfaceState"]
    assert focused["path"] == "event:/MachinePerception/AuditoryIcons/FocusedObject"
    assert focused["spatial"] is True and focused["maxInstances"] == 1
    assert interface["path"] == "event:/MachinePerception/Interface/State"
    assert interface["spatial"] is False
    assert set(focused["parameters"]) == {
        "IconConcept",
        "IconSalience",
        "IconConfidence",
        "DistanceMeters",
        "BeaconMode",
        "DwellSpeechActive",
    }
    assert focused["parameters"]["DistanceMeters"] == {"type": "continuous", "minimum": 0, "maximum": 8}
    assert focused["parameters"]["BeaconMode"] == {
        "type": "integer",
        "minimum": 0,
        "maximumExclusive": 3,
    }
    assert focused["ducking"] == {
        "owner": "authored_parameter_curve",
        "parameter": "DwellSpeechActive",
        "inactiveValue": 0,
        "activeValue": 1,
        "curveDb": [[0, 0], [1, -12]],
        "runtimeVolumeMultiplier": False,
    }
    runtime_path = (
        contract_path.parents[1] / "Assets" / "ConceptFlow" / "Runtime" / "FmodStudioPerceptionAudioBackend.cs"
    )
    runtime = runtime_path.read_text(encoding="utf-8")
    assert "setVolume(command.Gain)" in runtime
    assert "focusedBaseGain*duckGain" not in runtime
    assert 'setParameterByName("DwellSpeechActive",dwellSpeechActive?1f:0f,true)' in runtime
    icons = value["icons"]
    assert [item["index"] for item in icons] == list(range(5))
    assert icons[0]["concept"] == "neutral" and icons[0]["representational"] is False
    aliases = [alias for item in icons for alias in item["aliases"]]
    assert len(aliases) == len(set(aliases))
    assert {"car", "van", "bus", "truck", "motorcycle"}.issubset(set(aliases))
    assert len(value["interfaceStates"]) == 6
    print("[MPL_AUDIO_CONTRACT] status=Pass schema=v2 icons=5 aliasesUnique=True")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"[MPL_AUDIO_CONTRACT] status=Fail error={error}", file=sys.stderr)
        raise SystemExit(1)
