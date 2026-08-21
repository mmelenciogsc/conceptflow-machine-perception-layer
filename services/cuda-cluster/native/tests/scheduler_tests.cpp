// SPDX-License-Identifier: MIT OR Apache-2.0

#include "conceptflow/native/scheduler.hpp"

#include <atomic>
#include <barrier>
#include <cstdint>
#include <exception>
#include <functional>
#include <iostream>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <type_traits>
#include <utility>
#include <vector>

namespace cfn = conceptflow::native;

namespace {

constexpr std::uint64_t gibibytes(const std::uint64_t value) noexcept {
  return value * 1024U * 1024U * 1024U;
}

void expect(const bool condition, const std::string_view message) {
  if (!condition) {
    throw std::runtime_error(std::string(message));
  }
}

cfn::WorkerDescriptor worker(std::string id, std::vector<cfn::DeviceCapability> capabilities,
                             std::vector<std::string> models, const std::uint64_t memory_bytes,
                             const std::size_t active, const std::size_t queued,
                             const std::uint32_t latency_ms) {
  const auto device_id = "device-" + id;
  return cfn::WorkerDescriptor{
      .id = std::move(id),
      .device = cfn::DeviceDescriptor{.id = device_id,
                                      .name = "synthetic test device",
                                      .memory_bytes = memory_bytes,
                                      .capabilities = std::move(capabilities)},
      .health = cfn::WorkerHealth::healthy,
      .load = cfn::WorkerLoad{.active_requests = active,
                              .max_active_requests = 4U,
                              .queue_depth = queued,
                              .queue_capacity = 8U,
                              .estimated_latency_ms = latency_ms},
      .available_memory_bytes = memory_bytes,
      .supported_models = std::move(models),
  };
}

cfn::WorkerDescriptor single_slot_worker(std::string id, std::string model = "model") {
  auto result = worker(std::move(id), {cfn::DeviceCapability::cpu}, {std::move(model)},
                       gibibytes(8U), 0U, 1U, 10U);
  result.load.max_active_requests = 1U;
  result.load.queue_capacity = 1U;
  return result;
}

cfn::SelectionRequest request(std::string model = "model") {
  return cfn::SelectionRequest{
      .model_id = std::move(model),
      .required_memory_bytes = gibibytes(1U),
      .required_capabilities = {cfn::DeviceCapability::cpu},
      .latency_tier = cfn::LatencyTier::balanced,
  };
}

const cfn::WorkerDescriptor& only_worker(const std::vector<cfn::WorkerDescriptor>& snapshots) {
  expect(snapshots.size() == 1U, "expected exactly one worker snapshot");
  return snapshots.front();
}

void selection_enforces_constraints_and_latency() {
  cfn::WorkerRegistry registry;
  registry.upsert(worker("cpu", {cfn::DeviceCapability::cpu}, {"detector"}, gibibytes(32U),
                         0U, 0U, 5U));
  registry.upsert(worker("gpu-slow", {cfn::DeviceCapability::cuda}, {"detector"},
                         gibibytes(8U), 0U, 0U, 80U));
  registry.upsert(worker("gpu-fast", {cfn::DeviceCapability::cuda}, {"detector", "depth"},
                         gibibytes(8U), 0U, 0U, 10U));

  auto result = registry.select_and_reserve({
      .model_id = "detector",
      .required_memory_bytes = gibibytes(4U),
      .required_capabilities = {cfn::DeviceCapability::cuda},
      .latency_tier = cfn::LatencyTier::interactive,
  });
  expect(result.has_value(), "CUDA request should have an eligible worker");
  expect(result->worker().id == "gpu-fast", "interactive request should prefer lower latency");
  expect(result->cancel(), "first selection reservation should cancel");

  auto depth = registry.select_and_reserve({
      .model_id = "depth",
      .required_memory_bytes = gibibytes(4U),
      .required_capabilities = {cfn::DeviceCapability::cuda},
      .latency_tier = cfn::LatencyTier::balanced,
  });
  expect(depth.has_value() && depth->worker().id == "gpu-fast",
         "model support must be enforced");
  expect(depth->cancel(), "depth reservation should cancel");
}

void selection_prefers_least_loaded_worker() {
  cfn::WorkerRegistry registry;
  registry.upsert(worker("busy-fast", {cfn::DeviceCapability::cpu}, {"model"}, gibibytes(8U),
                         4U, 8U, 1U));
  registry.upsert(worker("idle-slower", {cfn::DeviceCapability::cpu}, {"model"}, gibibytes(8U),
                         0U, 0U, 25U));
  auto result = registry.select_and_reserve({
      .model_id = "model",
      .required_memory_bytes = gibibytes(1U),
      .required_capabilities = {cfn::DeviceCapability::cpu},
      .latency_tier = cfn::LatencyTier::batch,
  });
  expect(result.has_value() && result->worker().id == "idle-slower",
         "least-loaded worker should beat a saturated low-latency worker");
  expect(result->cancel(), "least-loaded reservation should cancel");

  expect(registry.update_load("busy-fast",
                              {.active_requests = 0U,
                               .max_active_requests = 4U,
                               .queue_depth = 0U,
                               .queue_capacity = 8U,
                               .estimated_latency_ms = 1U},
                              gibibytes(8U)),
         "known worker load should update without re-registration");
  auto refreshed = registry.select_and_reserve({
      .model_id = "model",
      .required_memory_bytes = gibibytes(1U),
      .required_capabilities = {cfn::DeviceCapability::cpu},
      .latency_tier = cfn::LatencyTier::batch,
  });
  expect(refreshed.has_value() && refreshed->worker().id == "busy-fast",
         "selection should consume refreshed worker load and latency");
  expect(refreshed->cancel(), "refreshed reservation should cancel");
}

void selection_excludes_only_fully_saturated_workers() {
  cfn::WorkerRegistry registry;
  registry.upsert(worker("fully-saturated", {cfn::DeviceCapability::cpu}, {"full"},
                         gibibytes(8U), 4U, 8U, 1U));
  registry.upsert(worker("active-headroom", {cfn::DeviceCapability::cpu}, {"active-open"},
                         gibibytes(8U), 3U, 8U, 10U));
  registry.upsert(worker("queue-headroom", {cfn::DeviceCapability::cpu}, {"queue-open"},
                         gibibytes(8U), 4U, 7U, 10U));

  const auto fully_saturated = registry.select_and_reserve({
      .model_id = "full",
      .required_memory_bytes = gibibytes(1U),
      .required_capabilities = {cfn::DeviceCapability::cpu},
      .latency_tier = cfn::LatencyTier::interactive,
  });
  expect(!fully_saturated.has_value(), "fully saturated worker must be excluded");

  auto active_headroom = registry.select_and_reserve({
      .model_id = "active-open",
      .required_memory_bytes = gibibytes(1U),
      .required_capabilities = {cfn::DeviceCapability::cpu},
      .latency_tier = cfn::LatencyTier::interactive,
  });
  expect(active_headroom.has_value() && active_headroom->worker().id == "active-headroom",
         "worker with active-request headroom must remain eligible");
  expect(active_headroom->cancel(), "active-headroom reservation should cancel");

  auto queue_headroom = registry.select_and_reserve({
      .model_id = "queue-open",
      .required_memory_bytes = gibibytes(1U),
      .required_capabilities = {cfn::DeviceCapability::cpu},
      .latency_tier = cfn::LatencyTier::interactive,
  });
  expect(queue_headroom.has_value() && queue_headroom->worker().id == "queue-headroom",
         "worker with queue headroom must remain eligible");
  expect(queue_headroom->cancel(), "queue-headroom reservation should cancel");
}

void health_degrades_fails_and_recovers() {
  cfn::WorkerRegistry registry({1U, 3U, 2U});
  registry.upsert(worker("alpha", {cfn::DeviceCapability::cpu}, {"model"}, gibibytes(8U), 0U,
                         0U, 10U));
  registry.upsert(worker("beta", {cfn::DeviceCapability::cpu}, {"model"}, gibibytes(8U), 0U,
                         0U, 10U));
  const cfn::SelectionRequest request{
      .model_id = "model",
      .required_memory_bytes = gibibytes(1U),
      .required_capabilities = {cfn::DeviceCapability::cpu},
      .latency_tier = cfn::LatencyTier::balanced,
  };

  auto initial = registry.select_and_reserve(request);
  expect(initial.has_value() && initial->worker().id == "alpha",
         "initial tie should select alpha");
  expect(initial->cancel(), "initial health selection should cancel");
  expect(registry.record_failure("alpha"), "known worker failure should be recorded");
  auto degraded = registry.select_and_reserve(request);
  expect(degraded.has_value() && degraded->worker().id == "beta",
         "degraded worker should lose to an equivalent healthy worker");
  expect(degraded->cancel(), "degraded comparison reservation should cancel");
  expect(registry.record_success("alpha", 10U), "first recovery success should be accepted");
  expect(registry.record_success("alpha", 10U), "second recovery success should be accepted");
  auto recovered = registry.select_and_reserve(request);
  expect(recovered.has_value() && recovered->worker().id == "alpha",
         "recovered worker should rejoin ties");
  expect(recovered->cancel(), "recovered health selection should cancel");

  expect(registry.record_failure("alpha"), "first terminal failure should be recorded");
  expect(registry.record_failure("alpha"), "second terminal failure should be recorded");
  expect(registry.record_failure("alpha"), "third terminal failure should be recorded");
  const auto snapshots = registry.snapshot();
  expect(snapshots.front().health == cfn::WorkerHealth::failed,
         "failure threshold should mark worker failed");
  auto after_failure = registry.select_and_reserve(request);
  expect(after_failure.has_value() && after_failure->worker().id == "beta",
         "failed worker must be excluded");
  expect(after_failure->cancel(), "post-failure reservation should cancel");
}

void concurrent_single_slot_reservation_is_atomic() {
  static_assert(!std::is_copy_constructible_v<cfn::WorkerLease>);
  static_assert(!std::is_copy_assignable_v<cfn::WorkerLease>);
  static_assert(std::is_nothrow_move_constructible_v<cfn::WorkerLease>);
  static_assert(std::is_nothrow_move_assignable_v<cfn::WorkerLease>);

  constexpr std::size_t contender_count = 32U;
  cfn::WorkerRegistry registry;
  registry.upsert(single_slot_worker("only"));
  const auto selection_request = request();
  std::barrier start_line(static_cast<std::ptrdiff_t>(contender_count));
  std::vector<std::optional<cfn::WorkerLease>> leases(contender_count);
  std::vector<std::thread> contenders;
  contenders.reserve(contender_count);
  for (std::size_t index = 0U; index < contender_count; ++index) {
    contenders.emplace_back([&registry, &selection_request, &start_line, &leases, index]() {
      start_line.arrive_and_wait();
      leases[index] = registry.select_and_reserve(selection_request);
    });
  }
  for (auto& contender : contenders) {
    contender.join();
  }

  std::size_t successes = 0U;
  for (const auto& lease : leases) {
    if (lease.has_value()) {
      ++successes;
    }
  }
  expect(successes == 1U, "exactly one concurrent reservation may claim the final slot");
  const auto reserved_snapshot = registry.snapshot();
  expect(only_worker(reserved_snapshot).load.active_requests == 1U,
         "snapshot must include the winning active reservation");
  expect(only_worker(reserved_snapshot).available_memory_bytes == gibibytes(7U),
         "snapshot must include reserved memory");

  for (auto& lease : leases) {
    if (lease.has_value()) {
      expect(lease->cancel(), "winning concurrent lease should cancel once");
    }
  }
  const auto released_snapshot = registry.snapshot();
  expect(only_worker(released_snapshot).load.active_requests == 0U,
         "cancel must release the winning slot");
  expect(only_worker(released_snapshot).available_memory_bytes == gibibytes(8U),
         "cancel must release the winning memory reservation");
}

void lease_terminal_paths_release_exactly_once() {
  {
    cfn::WorkerRegistry registry;
    registry.upsert(single_slot_worker("success"));
    auto lease = registry.select_and_reserve(request());
    expect(lease.has_value(), "success path should reserve");
    expect(lease->complete_success(20U), "success completion should release once");
    expect(!lease->complete_success(20U), "success completion must be idempotent");
    expect(!lease->cancel(), "completed success cannot cancel again");
    const auto snapshots = registry.snapshot();
    expect(only_worker(snapshots).load.active_requests == 0U,
           "success completion must release capacity");
    expect(only_worker(snapshots).available_memory_bytes == gibibytes(8U),
           "success completion must release memory");
    expect(only_worker(snapshots).load.estimated_latency_ms == 12U,
           "success completion must record latency");
  }

  {
    cfn::WorkerRegistry registry({1U, 1U, 2U});
    registry.upsert(single_slot_worker("failure"));
    auto lease = registry.select_and_reserve(request());
    expect(lease.has_value(), "failure path should reserve");
    expect(lease->complete_failure(), "failure completion should release once");
    expect(!lease->complete_failure(), "failure completion must be idempotent");
    const auto snapshots = registry.snapshot();
    expect(only_worker(snapshots).load.active_requests == 0U,
           "failure completion must release capacity");
    expect(only_worker(snapshots).health == cfn::WorkerHealth::failed,
           "failure completion must update worker health");
  }

  {
    cfn::WorkerRegistry registry;
    registry.upsert(single_slot_worker("cancel"));
    auto lease = registry.select_and_reserve(request());
    expect(lease.has_value(), "cancellation path should reserve");
    expect(lease->cancel(), "cancellation should release once");
    expect(!lease->cancel(), "cancellation must be idempotent");
    const auto snapshots = registry.snapshot();
    expect(only_worker(snapshots).load.active_requests == 0U,
           "cancellation must release capacity");
    expect(only_worker(snapshots).health == cfn::WorkerHealth::healthy,
           "cancellation must not penalize worker health");
  }

  {
    cfn::WorkerRegistry registry;
    registry.upsert(single_slot_worker("destructor"));
    {
      auto lease = registry.select_and_reserve(request());
      expect(lease.has_value(), "destructor path should reserve");
    }
    const auto snapshots = registry.snapshot();
    expect(only_worker(snapshots).load.active_requests == 0U,
           "lease destruction must release capacity");
    expect(only_worker(snapshots).available_memory_bytes == gibibytes(8U),
           "lease destruction must release memory");
  }
}

void moved_leases_transfer_one_reservation() {
  cfn::WorkerRegistry registry;
  auto descriptor = single_slot_worker("move");
  descriptor.load.max_active_requests = 2U;
  registry.upsert(std::move(descriptor));

  auto first = registry.select_and_reserve(request());
  auto second = registry.select_and_reserve(request());
  expect(first.has_value() && second.has_value(), "two slots should admit two leases");
  expect(only_worker(registry.snapshot()).load.active_requests == 2U,
         "both reservations must appear in the snapshot");

  *first = std::move(*second);
  expect(first->active(), "move destination must own the transferred reservation");
  expect(!second->active(), "move source must relinquish its reservation");
  expect(only_worker(registry.snapshot()).load.active_requests == 1U,
         "move assignment must release the destination's old reservation once");
  expect(!second->cancel(), "moved-from lease must not release the reservation");
  expect(first->cancel(), "move destination should release the transferred reservation");
  expect(!first->cancel(), "moved lease must not double-release");
  expect(only_worker(registry.snapshot()).load.active_requests == 0U,
         "all moved reservations must be released without underflow");
}

void lease_outlives_registry_safely() {
  std::optional<cfn::WorkerLease> lease;
  {
    cfn::WorkerRegistry registry;
    registry.upsert(single_slot_worker("lifetime"));
    lease = registry.select_and_reserve(request());
    expect(lease.has_value(), "lifetime path should reserve");
  }
  expect(lease->cancel(), "lease must release safely after registry destruction");
  expect(!lease->cancel(), "post-registry release must remain idempotent");
}

void load_updates_cannot_erase_live_reservations() {
  cfn::WorkerRegistry registry;
  registry.upsert(single_slot_worker("refresh"));
  auto lease = registry.select_and_reserve(request());
  expect(lease.has_value(), "load-refresh path should reserve");

  bool rejected = false;
  try {
    static_cast<void>(registry.update_load(
        "refresh",
        {.active_requests = 0U,
         .max_active_requests = 1U,
         .queue_depth = 1U,
         .queue_capacity = 1U,
         .estimated_latency_ms = 25U},
        gibibytes(8U)));
  } catch (const std::invalid_argument&) {
    rejected = true;
  }
  expect(rejected, "load refresh must not erase a live reservation");
  expect(only_worker(registry.snapshot()).load.active_requests == 1U,
         "rejected refresh must leave reservation accounting unchanged");

  expect(registry.update_load(
             "refresh",
             {.active_requests = 1U,
              .max_active_requests = 1U,
              .queue_depth = 1U,
              .queue_capacity = 1U,
              .estimated_latency_ms = 25U},
             gibibytes(7U)),
         "load refresh preserving the reservation should succeed");
  expect(lease->cancel(), "lease should release after a valid load refresh");
  const auto snapshots = registry.snapshot();
  expect(only_worker(snapshots).load.active_requests == 0U,
         "release after refresh must not underflow active load");
  expect(only_worker(snapshots).available_memory_bytes == gibibytes(8U),
         "release after refresh must restore reserved memory exactly once");
}

void unhealthy_and_saturated_workers_are_excluded() {
  cfn::WorkerRegistry registry;
  registry.upsert(single_slot_worker("failed", "failed-model"));
  registry.upsert(single_slot_worker("draining", "draining-model"));
  registry.upsert(single_slot_worker("degraded", "degraded-model"));
  auto saturated = single_slot_worker("saturated", "saturated-model");
  saturated.load.active_requests = saturated.load.max_active_requests;
  registry.upsert(std::move(saturated));

  expect(registry.record_failure("failed"), "first failure should record");
  expect(registry.record_failure("failed"), "second failure should record");
  expect(registry.record_failure("failed"), "third failure should record");
  expect(registry.set_draining("draining"), "worker should enter draining state");
  expect(registry.record_failure("degraded"), "degraded worker failure should record");

  expect(!registry.select_and_reserve(request("failed-model")).has_value(),
         "failed worker must not be selected");
  expect(!registry.select_and_reserve(request("draining-model")).has_value(),
         "draining worker must not be selected");
  expect(!registry.select_and_reserve(request("saturated-model")).has_value(),
         "fully saturated worker must not be selected");
  auto degraded = registry.select_and_reserve(request("degraded-model"));
  expect(degraded.has_value(), "degraded worker remains eligible when capacity exists");
  expect(degraded->worker().health == cfn::WorkerHealth::degraded,
         "lease snapshot must preserve degraded health");
  expect(degraded->cancel(), "degraded lease should cancel");
}

void queue_overflow_policies_are_explicit() {
  cfn::BoundedRequestQueue reject_queue(1U, cfn::OverflowPolicy::reject_newest);
  const auto accepted = reject_queue.submit({"one", "model", cfn::LatencyTier::balanced});
  const auto rejected = reject_queue.submit({"two", "model", cfn::LatencyTier::balanced});
  expect(accepted.accepted(), "first request should be accepted");
  expect(!rejected.accepted(), "full reject queue should reject newest");
  expect(rejected.handle->state() == cfn::RequestState::rejected,
         "rejected request should expose terminal state");

  cfn::BoundedRequestQueue drop_queue(1U, cfn::OverflowPolicy::drop_oldest);
  const auto old_request = drop_queue.submit({"old", "model", cfn::LatencyTier::batch});
  const auto new_request = drop_queue.submit({"new", "model", cfn::LatencyTier::interactive});
  expect(new_request.status == cfn::AdmissionStatus::accepted_after_drop,
         "drop queue should report accepted-after-drop");
  expect(new_request.dropped_request_id == std::optional<std::string>{"old"},
         "drop result should identify the displaced request");
  expect(old_request.handle->state() == cfn::RequestState::dropped,
         "displaced request should expose dropped state");
}

void cancellation_transitions_are_observable() {
  cfn::BoundedRequestQueue queue(2U, cfn::OverflowPolicy::reject_newest);
  const auto queued = queue.submit({"queued", "model", cfn::LatencyTier::balanced});
  expect(queue.cancel(queued.handle), "queued request cancellation should succeed");
  expect(queued.handle->state() == cfn::RequestState::cancelled,
         "queued cancellation should become terminal");
  expect(queue.size() == 0U, "queued cancellation should release capacity");

  const auto dispatched = queue.submit({"active", "model", cfn::LatencyTier::interactive});
  const auto work = queue.try_pop();
  expect(work.has_value(), "submitted request should dispatch");
  expect(queue.cancel(dispatched.handle), "active cancellation should be requested");
  expect(dispatched.handle->cancellation_requested(),
         "active worker should observe cancellation request");
  expect(queue.complete(dispatched.handle), "completion should acknowledge cancellation");
  expect(dispatched.handle->state() == cfn::RequestState::cancelled,
         "cancelled active request should not become completed");

  cfn::BoundedRequestQueue other_queue(1U, cfn::OverflowPolicy::reject_newest);
  const auto foreign = other_queue.submit({"foreign", "model", cfn::LatencyTier::balanced});
  expect(!queue.cancel(foreign.handle), "queue must reject a handle owned by another queue");
  expect(foreign.handle->state() == cfn::RequestState::queued,
         "foreign cancellation attempt must not mutate request state");
}

void concurrent_admission_remains_bounded() {
  constexpr std::size_t request_count = 32U;
  cfn::BoundedRequestQueue queue(request_count, cfn::OverflowPolicy::reject_newest);
  std::atomic_size_t accepted{0U};
  std::vector<std::thread> submitters;
  submitters.reserve(request_count);
  for (std::size_t index = 0U; index < request_count; ++index) {
    submitters.emplace_back([&queue, &accepted, index]() {
      const auto result = queue.submit(
          {"concurrent-" + std::to_string(index), "model", cfn::LatencyTier::balanced});
      if (result.accepted()) {
        accepted.fetch_add(1U, std::memory_order_relaxed);
      }
    });
  }
  for (auto& submitter : submitters) {
    submitter.join();
  }
  expect(accepted.load(std::memory_order_relaxed) == request_count,
         "all submissions within capacity should be accepted");
  expect(queue.size() == request_count, "concurrent queue depth must equal accepted count");
  expect(queue.size() <= queue.capacity(), "concurrent admission must remain bounded");
}

void no_eligible_worker_is_reported() {
  cfn::WorkerRegistry registry;
  registry.upsert(worker("cpu", {cfn::DeviceCapability::cpu}, {"small"}, gibibytes(2U), 0U, 0U,
                         5U));
  const auto missing = registry.select_and_reserve({
      .model_id = "large",
      .required_memory_bytes = gibibytes(4U),
      .required_capabilities = {cfn::DeviceCapability::cuda},
      .latency_tier = cfn::LatencyTier::interactive,
  });
  expect(!missing.has_value(), "unsatisfied capability/model/memory request must return no worker");

  expect(registry.set_draining("cpu"), "known worker should enter draining state");
  const auto draining = registry.select_and_reserve({
      .model_id = "small",
      .required_memory_bytes = gibibytes(1U),
      .required_capabilities = {cfn::DeviceCapability::cpu},
      .latency_tier = cfn::LatencyTier::batch,
  });
  expect(!draining.has_value(), "draining worker must be excluded");
}

void deterministic_ties_use_worker_id() {
  cfn::WorkerRegistry registry;
  registry.upsert(worker("zeta", {cfn::DeviceCapability::cpu}, {"model"}, gibibytes(4U), 1U, 1U,
                         20U));
  registry.upsert(worker("alpha", {cfn::DeviceCapability::cpu}, {"model"}, gibibytes(4U), 1U,
                         1U, 20U));
  const cfn::SelectionRequest request{
      .model_id = "model",
      .required_memory_bytes = gibibytes(1U),
      .required_capabilities = {cfn::DeviceCapability::cpu},
      .latency_tier = cfn::LatencyTier::balanced,
  };
  for (std::size_t iteration = 0U; iteration < 20U; ++iteration) {
    auto result = registry.select_and_reserve(request);
    expect(result.has_value() && result->worker().id == "alpha",
           "equal scores must consistently select lexical worker id");
    expect(result->cancel(), "tie reservation should cancel before the next iteration");
  }
}

}  // namespace

int main() {
  const std::vector<std::pair<std::string_view, std::function<void()>>> tests{
      {"selection_enforces_constraints_and_latency", selection_enforces_constraints_and_latency},
      {"selection_prefers_least_loaded_worker", selection_prefers_least_loaded_worker},
      {"selection_excludes_only_fully_saturated_workers",
       selection_excludes_only_fully_saturated_workers},
      {"health_degrades_fails_and_recovers", health_degrades_fails_and_recovers},
      {"concurrent_single_slot_reservation_is_atomic",
       concurrent_single_slot_reservation_is_atomic},
      {"lease_terminal_paths_release_exactly_once",
       lease_terminal_paths_release_exactly_once},
      {"moved_leases_transfer_one_reservation", moved_leases_transfer_one_reservation},
      {"lease_outlives_registry_safely", lease_outlives_registry_safely},
      {"load_updates_cannot_erase_live_reservations",
       load_updates_cannot_erase_live_reservations},
      {"unhealthy_and_saturated_workers_are_excluded",
       unhealthy_and_saturated_workers_are_excluded},
      {"queue_overflow_policies_are_explicit", queue_overflow_policies_are_explicit},
      {"cancellation_transitions_are_observable", cancellation_transitions_are_observable},
      {"concurrent_admission_remains_bounded", concurrent_admission_remains_bounded},
      {"no_eligible_worker_is_reported", no_eligible_worker_is_reported},
      {"deterministic_ties_use_worker_id", deterministic_ties_use_worker_id},
  };

  std::size_t failures = 0U;
  for (const auto& [name, test] : tests) {
    try {
      test();
      std::cout << "PASS " << name << '\n';
    } catch (const std::exception& error) {
      ++failures;
      std::cerr << "FAIL " << name << ": " << error.what() << '\n';
    } catch (...) {
      ++failures;
      std::cerr << "FAIL " << name << ": unknown exception\n";
    }
  }

  if (failures != 0U) {
    std::cerr << failures << " test(s) failed\n";
    return 1;
  }
  std::cout << tests.size() << " test(s) passed\n";
  return 0;
}
