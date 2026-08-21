# SPDX-License-Identifier: MIT OR Apache-2.0
from __future__ import annotations

from conceptflow.mpl.v1 import perception_pb2 as pb
from conceptflow_mpl_host.scheduler import CueScheduler, Verbosity


NOW = 1_000_000_000


def test_priority_preempts_active(cue_factory) -> None:
    scheduler = CueScheduler(capacity=3)
    scheduler.schedule(cue_factory("low", priority=10), now_ns=NOW)
    outcome = scheduler.schedule(
        cue_factory("critical", priority=100, urgency=pb.URGENCY_CRITICAL, description="stop"),
        now_ns=NOW,
    )
    assert outcome.accepted
    assert outcome.preempted_ids == ("low",)
    assert outcome.dispatched_cue.cue_id == "critical"
    assert scheduler.active.cue_id == "critical"
    assert scheduler.active.speech.interrupt is True


def test_accepted_pending_cue_is_not_dispatched(cue_factory) -> None:
    scheduler = CueScheduler(capacity=3, cooldown_ms=0)
    active = scheduler.schedule(cue_factory("active", priority=90), now_ns=NOW)
    pending = scheduler.schedule(
        cue_factory("pending", priority=10, description="wall"),
        now_ns=NOW,
    )

    assert active.dispatched_cue.cue_id == "active"
    assert pending.accepted
    assert pending.cue.cue_id == "pending"
    assert pending.dispatched_cue is None
    assert scheduler.active.cue_id == "active"
    assert scheduler.pending_count == 1


def test_ttl_rejects_expired_cue(cue_factory) -> None:
    scheduler = CueScheduler()
    outcome = scheduler.schedule(cue_factory(ttl_ms=10), now_ns=NOW + 10_000_000)
    assert not outcome.accepted
    assert "expired" in outcome.reason


def test_future_timestamp_is_rejected(cue_factory) -> None:
    scheduler = CueScheduler()
    outcome = scheduler.schedule(cue_factory(now_ns=NOW + 1), now_ns=NOW)
    assert not outcome.accepted
    assert "future" in outcome.reason


def test_dedup_cooldown(cue_factory) -> None:
    scheduler = CueScheduler(cooldown_ms=100)
    assert scheduler.schedule(cue_factory("one"), now_ns=NOW).accepted
    duplicate = cue_factory("two", now_ns=NOW + 1, description="Door Ahead")
    assert not scheduler.schedule(duplicate, now_ns=NOW + 50_000_000).accepted
    later = cue_factory("three", now_ns=NOW + 1, description="door ahead")
    assert scheduler.schedule(later, now_ns=NOW + 100_000_000).accepted


def test_standard_and_minimal_verbosity_choose_modalities(cue_factory) -> None:
    standard = CueScheduler(verbosity=Verbosity.STANDARD)
    standard_outcome = standard.schedule(cue_factory(priority=50), now_ns=NOW)
    assert not standard_outcome.cue.HasField("speech")
    assert standard_outcome.cue.HasField("earcon")
    assert standard_outcome.cue.HasField("haptic")
    minimal = CueScheduler(verbosity=Verbosity.MINIMAL)
    critical = minimal.schedule(cue_factory(priority=100, urgency=pb.URGENCY_CRITICAL), now_ns=NOW)
    assert critical.cue.HasField("speech")


def test_cancellation_removes_active_and_pending(cue_factory) -> None:
    scheduler = CueScheduler(capacity=3, cooldown_ms=0)
    scheduler.schedule(cue_factory("active", priority=50), now_ns=NOW)
    scheduler.schedule(cue_factory("pending", priority=10, description="wall"), now_ns=NOW)
    cancel = cue_factory("cancel", description="")
    cancel.cancel.cue_ids.extend(["active", "pending"])
    outcome = scheduler.schedule(cancel, now_ns=NOW)
    assert outcome.accepted
    assert set(outcome.cancelled_ids) == {"active", "pending"}
    assert scheduler.active is None


def test_supersession_removes_named_cue(cue_factory) -> None:
    scheduler = CueScheduler(capacity=3, cooldown_ms=0)
    scheduler.schedule(cue_factory("old", priority=40), now_ns=NOW)
    replacement = cue_factory("new", priority=60, description="nearer door")
    replacement.supersede.cue_ids.append("old")
    outcome = scheduler.schedule(replacement, now_ns=NOW)
    assert outcome.accepted
    assert "old" in outcome.preempted_ids
    assert scheduler.active.cue_id == "new"


def test_capacity_rejects_lower_ranked_cue(cue_factory) -> None:
    scheduler = CueScheduler(capacity=2, cooldown_ms=0)
    scheduler.schedule(cue_factory("active", priority=90), now_ns=NOW)
    scheduler.schedule(cue_factory("pending", priority=80, description="one"), now_ns=NOW)
    outcome = scheduler.schedule(cue_factory("low", priority=10, description="two"), now_ns=NOW)
    assert not outcome.accepted
    assert scheduler.pending_count == 1


def test_expiration_activates_next_cue(cue_factory) -> None:
    scheduler = CueScheduler(capacity=3, cooldown_ms=0)
    scheduler.schedule(cue_factory("short", ttl_ms=1, priority=90), now_ns=NOW)
    scheduler.schedule(cue_factory("next", ttl_ms=100, priority=50, description="next"), now_ns=NOW)
    scheduler.schedule(
        cue_factory("probe", now_ns=NOW + 2_000_000, priority=1, description="probe"), now_ns=NOW + 2_000_000
    )
    assert scheduler.active.cue_id == "next"


def test_complete_after_active_expiry_keeps_highest_ranked_pending(cue_factory) -> None:
    scheduler = CueScheduler(capacity=4, cooldown_ms=0)
    scheduler.schedule(cue_factory("expired", ttl_ms=1, priority=100), now_ns=NOW)
    scheduler.schedule(
        cue_factory("highest", ttl_ms=100, priority=80, description="highest"),
        now_ns=NOW,
    )
    scheduler.schedule(
        cue_factory("lower", ttl_ms=100, priority=40, description="lower"),
        now_ns=NOW,
    )

    activated = scheduler.complete_active(now_ns=NOW + 2_000_000)

    assert activated is not None
    assert activated.cue_id == "highest"
    assert scheduler.active.cue_id == "highest"
    assert scheduler.pending_count == 1


def test_cooldown_state_prunes_at_expiry(cue_factory) -> None:
    scheduler = CueScheduler(capacity=4, cooldown_ms=100, cooldown_capacity=2)
    assert scheduler.schedule(cue_factory("one", description="one"), now_ns=NOW).accepted
    assert scheduler.schedule(cue_factory("two", description="two"), now_ns=NOW).accepted
    assert scheduler.cooldown_count == 2

    later = cue_factory("later", now_ns=NOW + 100_000_000, description="later")
    assert scheduler.schedule(later, now_ns=NOW + 100_000_000).accepted
    assert scheduler.cooldown_count == 1


def test_cooldown_bound_never_forgets_a_live_deduplication_signature(cue_factory) -> None:
    scheduler = CueScheduler(capacity=4, cooldown_ms=1_000, cooldown_capacity=2)
    assert scheduler.schedule(cue_factory("one", description="one"), now_ns=NOW).accepted
    assert scheduler.schedule(cue_factory("two", description="two"), now_ns=NOW).accepted

    full = scheduler.schedule(cue_factory("three", description="three"), now_ns=NOW)
    duplicate = scheduler.schedule(cue_factory("duplicate", description="one"), now_ns=NOW + 1)

    assert not full.accepted
    assert full.reason == "cue cooldown capacity reached"
    assert not duplicate.accepted
    assert duplicate.reason == "duplicate cue is inside cooldown"
    assert scheduler.cooldown_count == 2
