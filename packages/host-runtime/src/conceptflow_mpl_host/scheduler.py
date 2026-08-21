# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded BVI cue scheduling with explicit multimodal policy."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from conceptflow.mpl.v1 import perception_pb2 as pb


class Verbosity(StrEnum):
    MINIMAL = "minimal"
    STANDARD = "standard"
    DETAILED = "detailed"


@dataclass(frozen=True, slots=True)
class ScheduleOutcome:
    accepted: bool
    reason: str
    cue: pb.PerceptionCue | None = None
    cancelled_ids: tuple[str, ...] = ()
    preempted_ids: tuple[str, ...] = ()
    dispatched_cue: pb.PerceptionCue | None = None


class CueScheduler:
    def __init__(
        self,
        *,
        capacity: int = 8,
        cooldown_ms: int = 1_000,
        cooldown_capacity: int = 1_024,
        verbosity: Verbosity = Verbosity.STANDARD,
    ) -> None:
        if capacity <= 0 or cooldown_ms < 0 or cooldown_capacity <= 0:
            raise ValueError("scheduler limits are invalid")
        self._capacity = capacity
        self._cooldown_ns = cooldown_ms * 1_000_000
        self._cooldown_capacity = cooldown_capacity
        self._verbosity = verbosity
        self._active: pb.PerceptionCue | None = None
        self._pending: list[pb.PerceptionCue] = []
        self._last_signature_ns: dict[tuple[int, str, int], int] = {}

    @property
    def active(self) -> pb.PerceptionCue | None:
        return self._active

    @property
    def pending_count(self) -> int:
        return len(self._pending)

    @property
    def cooldown_count(self) -> int:
        return len(self._last_signature_ns)

    def _expired(self, cue: pb.PerceptionCue, now_ns: int) -> bool:
        return now_ns >= cue.created_monotonic_timestamp_ns + cue.ttl_ms * 1_000_000

    def _rank(self, cue: pb.PerceptionCue) -> tuple[int, int, int]:
        return (int(cue.urgency), int(cue.priority), -int(cue.created_monotonic_timestamp_ns))

    def _signature(self, cue: pb.PerceptionCue) -> tuple[int, str, int]:
        return (int(cue.category), cue.description.strip().casefold(), int(cue.direction))

    def _prune_cooldowns(self, now_ns: int) -> None:
        expired = [
            signature
            for signature, last_ns in self._last_signature_ns.items()
            if now_ns >= last_ns and now_ns - last_ns >= self._cooldown_ns
        ]
        for signature in expired:
            del self._last_signature_ns[signature]

    def _apply_verbosity(self, cue: pb.PerceptionCue) -> pb.PerceptionCue:
        adjusted = pb.PerceptionCue()
        adjusted.CopyFrom(cue)
        if self._verbosity == Verbosity.MINIMAL and cue.urgency < pb.URGENCY_CRITICAL:
            adjusted.ClearField("speech")
        elif self._verbosity == Verbosity.STANDARD and cue.priority < 70:
            adjusted.ClearField("speech")
        if cue.urgency == pb.URGENCY_CRITICAL and adjusted.HasField("speech"):
            adjusted.speech.interrupt = True
        return adjusted

    def _remove_ids(self, ids: set[str]) -> tuple[str, ...]:
        removed: list[str] = []
        if self._active is not None and self._active.cue_id in ids:
            removed.append(self._active.cue_id)
            self._active = None
        retained: list[pb.PerceptionCue] = []
        for cue in self._pending:
            if cue.cue_id in ids:
                removed.append(cue.cue_id)
            else:
                retained.append(cue)
        self._pending = retained
        return tuple(removed)

    def _purge(self, now_ns: int) -> None:
        if self._active is not None and self._expired(self._active, now_ns):
            self._active = None
        self._pending = [cue for cue in self._pending if not self._expired(cue, now_ns)]
        if self._active is None:
            self._activate_next()

    def _activate_next(self) -> pb.PerceptionCue | None:
        if not self._pending:
            self._active = None
            return None
        index = max(range(len(self._pending)), key=lambda item: self._rank(self._pending[item]))
        self._active = self._pending.pop(index)
        return self._active

    def schedule(self, cue: pb.PerceptionCue, *, now_ns: int) -> ScheduleOutcome:
        self._prune_cooldowns(now_ns)
        active_before = self._active
        if active_before is not None and self._expired(active_before, now_ns):
            active_before = None
        self._purge(now_ns)

        def outcome(
            accepted: bool,
            reason: str,
            *,
            scheduled_cue: pb.PerceptionCue | None = None,
            cancelled_ids: tuple[str, ...] = (),
            preempted_ids: tuple[str, ...] = (),
        ) -> ScheduleOutcome:
            dispatched_cue = self._active if self._active is not active_before else None
            return ScheduleOutcome(
                accepted,
                reason,
                cue=scheduled_cue,
                cancelled_ids=cancelled_ids,
                preempted_ids=preempted_ids,
                dispatched_cue=dispatched_cue,
            )

        control_only = bool(cue.cancel.cue_ids) and not cue.description and not cue.supersede.cue_ids
        if not cue.cue_id or cue.ttl_ms <= 0 or cue.priority > 100:
            return outcome(False, "cue identity, TTL, or priority is invalid")
        if not cue.description and not control_only:
            return outcome(False, "non-cancellation cue description is required")
        if not 0.0 <= cue.confidence <= 1.0:
            return outcome(False, "cue confidence is invalid")
        if cue.created_monotonic_timestamp_ns > now_ns:
            return outcome(False, "cue timestamp is in the future")
        if self._expired(cue, now_ns):
            return outcome(False, "cue expired before scheduling")

        cancel_ids = set(cue.cancel.cue_ids)
        cancelled = self._remove_ids(cancel_ids) if cancel_ids else ()
        if control_only:
            if self._active is None:
                self._activate_next()
            return outcome(True, "cancellation applied", cancelled_ids=cancelled)

        superseded = self._remove_ids(set(cue.supersede.cue_ids))
        adjusted = self._apply_verbosity(cue)
        signature = self._signature(adjusted)
        last_ns = self._last_signature_ns.get(signature)
        if last_ns is not None and now_ns - last_ns < self._cooldown_ns:
            return outcome(False, "duplicate cue is inside cooldown", cancelled_ids=cancelled)
        if signature not in self._last_signature_ns and len(self._last_signature_ns) >= self._cooldown_capacity:
            return outcome(False, "cue cooldown capacity reached", cancelled_ids=cancelled)

        preempted: tuple[str, ...] = superseded
        if self._active is None:
            self._active = adjusted
        elif self._rank(adjusted) > self._rank(self._active):
            preempted = preempted + (self._active.cue_id,)
            self._active = adjusted
        else:
            occupied = 1 + len(self._pending)
            if occupied >= self._capacity:
                if not self._pending:
                    return outcome(False, "cue capacity reached", cancelled_ids=cancelled)
                lowest_index = min(range(len(self._pending)), key=lambda item: self._rank(self._pending[item]))
                lowest = self._pending[lowest_index]
                if self._rank(adjusted) <= self._rank(lowest):
                    return outcome(False, "cue capacity reached", cancelled_ids=cancelled)
                preempted = preempted + (lowest.cue_id,)
                self._pending.pop(lowest_index)
            self._pending.append(adjusted)
        self._last_signature_ns[signature] = now_ns
        return outcome(
            True,
            "cue scheduled",
            scheduled_cue=adjusted,
            cancelled_ids=cancelled,
            preempted_ids=preempted,
        )

    def complete_active(self, *, now_ns: int) -> pb.PerceptionCue | None:
        self._prune_cooldowns(now_ns)
        active_before = self._active
        active_expired = active_before is not None and self._expired(active_before, now_ns)
        self._purge(now_ns)
        if active_before is None or active_expired:
            return self._active
        self._active = None
        return self._activate_next()
