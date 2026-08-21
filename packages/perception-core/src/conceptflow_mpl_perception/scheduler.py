# SPDX-License-Identifier: MIT OR Apache-2.0
"""One deterministic priority scheduler for all perceptual output lanes."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import IntEnum


class PriorityLane(IntEnum):
    PERIODIC_SCENE = 10
    ORDINARY_ICON = 30
    SALIENT_ICON = 50
    USER_SCENE = 70
    HAPTIC_TRANSITION = 80
    GEOMETRY = 90
    RAPID_GEOMETRY = 100


@dataclass(frozen=True, slots=True)
class ScheduledCue:
    cue_id: str
    deduplication_key: str
    lane: PriorityLane
    created_ns: int
    expiry_ns: int
    duration_ms: int
    payload: object
    duck_others: float = 0.0
    audio_voice_cost: int = 1
    speech_slot_cost: int = 0
    haptic_slot_cost: int = 0

    def __post_init__(self) -> None:
        if not self.cue_id or not self.deduplication_key or self.expiry_ns <= self.created_ns:
            raise ValueError("scheduled cue identity and timing must be valid")
        if self.duration_ms <= 0 or not 0.0 <= self.duck_others <= 1.0:
            raise ValueError("scheduled cue duration or ducking is invalid")
        if min(self.audio_voice_cost, self.speech_slot_cost, self.haptic_slot_cost) < 0:
            raise ValueError("scheduled cue resource costs must be nonnegative")
        if self.audio_voice_cost + self.speech_slot_cost + self.haptic_slot_cost <= 0:
            raise ValueError("scheduled cue must consume at least one output resource")


@dataclass(slots=True)
class SchedulerCounters:
    generated: int = 0
    rendered: int = 0
    suppressed_similarity: int = 0
    suppressed_capacity: int = 0
    superseded: int = 0
    stale: int = 0
    expired: int = 0
    interrupted: int = 0


@dataclass(slots=True)
class PerceptualPriorityScheduler:
    max_concurrent_voices: int = 6
    max_concurrent_speech: int = 1
    max_concurrent_haptics: int = 1
    cooldown_ms: int = 180
    counters: SchedulerCounters = field(default_factory=SchedulerCounters)
    _pending: dict[str, ScheduledCue] = field(default_factory=dict)
    _last_rendered: dict[str, int] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if min(self.max_concurrent_voices, self.max_concurrent_speech, self.max_concurrent_haptics) <= 0:
            raise ValueError("scheduler capacities must be positive")
        if self.cooldown_ms < 0:
            raise ValueError("scheduler limits are invalid")

    def submit(self, cue: ScheduledCue, now_ns: int) -> bool:
        self.counters.generated += 1
        if now_ns > cue.expiry_ns:
            self.counters.expired += 1
            return False
        last = self._last_rendered.get(cue.deduplication_key)
        if last is not None and now_ns - last < self.cooldown_ms * 1_000_000:
            self.counters.suppressed_similarity += 1
            return False
        previous = self._pending.get(cue.deduplication_key)
        if previous is not None:
            if (cue.lane, cue.created_ns) <= (previous.lane, previous.created_ns):
                self.counters.superseded += 1
                return False
            self.counters.superseded += 1
        self._pending[cue.deduplication_key] = cue
        return True

    def dispatch(self, now_ns: int) -> tuple[ScheduledCue, ...]:
        live = []
        for key, cue in list(self._pending.items()):
            if now_ns > cue.expiry_ns:
                self.counters.expired += 1
                del self._pending[key]
            else:
                live.append(cue)
        live.sort(key=lambda item: (-int(item.lane), item.created_ns, item.cue_id))
        chosen: list[ScheduledCue] = []
        audio_voices = speech_slots = haptic_slots = 0
        for cue in live:
            if (
                audio_voices + cue.audio_voice_cost > self.max_concurrent_voices
                or speech_slots + cue.speech_slot_cost > self.max_concurrent_speech
                or haptic_slots + cue.haptic_slot_cost > self.max_concurrent_haptics
            ):
                self.counters.suppressed_capacity += 1
                continue
            chosen.append(cue)
            audio_voices += cue.audio_voice_cost
            speech_slots += cue.speech_slot_cost
            haptic_slots += cue.haptic_slot_cost
        for cue in chosen:
            self._pending.pop(cue.deduplication_key, None)
            self._last_rendered[cue.deduplication_key] = now_ns
        self.counters.rendered += len(chosen)
        return tuple(chosen)

    def trace(self) -> dict[str, int]:
        return {
            name: getattr(self.counters, name)
            for name in (
                "generated",
                "rendered",
                "suppressed_similarity",
                "suppressed_capacity",
                "superseded",
                "stale",
                "expired",
                "interrupted",
            )
        }
