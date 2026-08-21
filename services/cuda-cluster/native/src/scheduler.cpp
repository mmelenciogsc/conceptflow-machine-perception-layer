// SPDX-License-Identifier: MIT OR Apache-2.0

#include "conceptflow/native/scheduler.hpp"

#include <algorithm>
#include <limits>
#include <stdexcept>
#include <utility>

namespace conceptflow::native {
namespace detail {

struct WorkerRecord {
  WorkerDescriptor descriptor;
  std::uint32_t consecutive_failures{};
  std::uint32_t consecutive_successes{};
  std::size_t active_reservations{};
  std::size_t queue_reservations{};
  std::uint64_t reserved_memory_bytes{};
};

struct WorkerRegistryState {
  explicit WorkerRegistryState(const HealthPolicy health_policy) : policy(health_policy) {}

  HealthPolicy policy;
  mutable std::mutex mutex;
  std::map<std::string, WorkerRecord, std::less<>> workers;
};

}  // namespace detail

namespace {

template <typename T>
void sort_and_deduplicate(std::vector<T>& values) {
  std::sort(values.begin(), values.end());
  values.erase(std::unique(values.begin(), values.end()), values.end());
}

[[nodiscard]] bool contains_all(const std::vector<DeviceCapability>& available,
                                const std::vector<DeviceCapability>& required) {
  return std::all_of(required.begin(), required.end(), [&available](const auto capability) {
    return std::binary_search(available.begin(), available.end(), capability);
  });
}

[[nodiscard]] std::uint32_t saturating_increment(const std::uint32_t value) noexcept {
  if (value == std::numeric_limits<std::uint32_t>::max()) {
    return value;
  }
  return value + 1U;
}

[[nodiscard]] long double tier_budget_ms(const LatencyTier tier) noexcept {
  switch (tier) {
    case LatencyTier::interactive:
      return 50.0L;
    case LatencyTier::balanced:
      return 200.0L;
    case LatencyTier::batch:
      return 1000.0L;
  }
  return 200.0L;
}

[[nodiscard]] long double tier_latency_weight(const LatencyTier tier) noexcept {
  switch (tier) {
    case LatencyTier::interactive:
      return 8.0L;
    case LatencyTier::balanced:
      return 3.0L;
    case LatencyTier::batch:
      return 1.0L;
  }
  return 3.0L;
}

[[nodiscard]] long double worker_score(const WorkerDescriptor& worker,
                                       const LatencyTier tier) noexcept {
  const auto active = static_cast<long double>(worker.load.active_requests);
  const auto max_active = static_cast<long double>(worker.load.max_active_requests);
  const auto queued = static_cast<long double>(worker.load.queue_depth);
  const auto queue_capacity = static_cast<long double>(worker.load.queue_capacity);
  const auto latency = static_cast<long double>(worker.load.estimated_latency_ms);

  const long double normalized_load = (active / max_active) + (queued / queue_capacity);
  const long double latency_ratio = latency / tier_budget_ms(tier);
  const long double health_penalty =
      worker.health == WorkerHealth::degraded ? 1000.0L : 0.0L;
  return health_penalty + (normalized_load * 100.0L) +
         (latency_ratio * tier_latency_weight(tier));
}

void apply_success(detail::WorkerRecord& record, const HealthPolicy& policy,
                   const std::uint32_t latency_ms) noexcept {
  if (record.descriptor.health == WorkerHealth::failed ||
      record.descriptor.health == WorkerHealth::draining) {
    return;
  }

  const auto previous_latency =
      static_cast<std::uint64_t>(record.descriptor.load.estimated_latency_ms);
  const auto updated_latency = ((previous_latency * 3U) + latency_ms) / 4U;
  record.descriptor.load.estimated_latency_ms = static_cast<std::uint32_t>(updated_latency);
  record.consecutive_failures = 0U;
  record.consecutive_successes = saturating_increment(record.consecutive_successes);
  if (record.descriptor.health == WorkerHealth::degraded &&
      record.consecutive_successes >= policy.successes_before_recovery) {
    record.descriptor.health = WorkerHealth::healthy;
    record.consecutive_successes = 0U;
  }
}

void apply_failure(detail::WorkerRecord& record, const HealthPolicy& policy) noexcept {
  if (record.descriptor.health == WorkerHealth::failed ||
      record.descriptor.health == WorkerHealth::draining) {
    return;
  }

  record.consecutive_successes = 0U;
  record.consecutive_failures = saturating_increment(record.consecutive_failures);
  if (record.consecutive_failures >= policy.failures_before_failed) {
    record.descriptor.health = WorkerHealth::failed;
  } else if (record.consecutive_failures >= policy.failures_before_degraded) {
    record.descriptor.health = WorkerHealth::degraded;
  }
}

}  // namespace

WorkerLease::WorkerLease(std::shared_ptr<detail::WorkerRegistryState> state,
                         std::string worker_id, const bool active_slot,
                         const std::uint64_t reserved_memory_bytes,
                         SelectionDecision decision)
    : state_(std::move(state)),
      worker_id_(std::move(worker_id)),
      active_slot_(active_slot),
      reserved_memory_bytes_(reserved_memory_bytes),
      decision_(std::move(decision)),
      active_(true) {}

WorkerLease::~WorkerLease() { static_cast<void>(cancel()); }

WorkerLease::WorkerLease(WorkerLease&& other) noexcept
    : state_(std::move(other.state_)),
      worker_id_(std::move(other.worker_id_)),
      active_slot_(other.active_slot_),
      reserved_memory_bytes_(other.reserved_memory_bytes_),
      decision_(std::move(other.decision_)),
      active_(std::exchange(other.active_, false)) {
  other.reserved_memory_bytes_ = 0U;
}

WorkerLease& WorkerLease::operator=(WorkerLease&& other) noexcept {
  if (this == &other) {
    return *this;
  }

  static_cast<void>(cancel());
  state_ = std::move(other.state_);
  worker_id_ = std::move(other.worker_id_);
  active_slot_ = other.active_slot_;
  reserved_memory_bytes_ = other.reserved_memory_bytes_;
  decision_ = std::move(other.decision_);
  active_ = std::exchange(other.active_, false);
  other.reserved_memory_bytes_ = 0U;
  return *this;
}

const SelectionDecision& WorkerLease::decision() const noexcept { return decision_; }

const WorkerDescriptor& WorkerLease::worker() const noexcept { return decision_.worker; }

bool WorkerLease::active() const noexcept { return active_; }

bool WorkerLease::complete_success(const std::uint32_t latency_ms) noexcept {
  return release(true, false, latency_ms);
}

bool WorkerLease::complete_failure() noexcept { return release(false, true, 0U); }

bool WorkerLease::cancel() noexcept { return release(false, false, 0U); }

bool WorkerLease::release(const bool succeeded, const bool failed,
                          const std::uint32_t latency_ms) noexcept {
  if (!active_) {
    return false;
  }

  active_ = false;
  auto state = std::move(state_);
  if (!state) {
    return false;
  }

  std::scoped_lock lock(state->mutex);
  const auto iterator = state->workers.find(worker_id_);
  if (iterator == state->workers.end()) {
    return false;
  }

  auto& record = iterator->second;
  auto& load = record.descriptor.load;
  const bool invalid_slot =
      active_slot_ ? (record.active_reservations == 0U || load.active_requests == 0U)
                   : (record.queue_reservations == 0U || load.queue_depth == 0U);
  if (invalid_slot || record.reserved_memory_bytes < reserved_memory_bytes_ ||
      reserved_memory_bytes_ > record.descriptor.device.memory_bytes ||
      record.descriptor.available_memory_bytes >
          record.descriptor.device.memory_bytes - reserved_memory_bytes_) {
    return false;
  }

  if (active_slot_) {
    --record.active_reservations;
    --load.active_requests;
  } else {
    --record.queue_reservations;
    --load.queue_depth;
  }

  record.reserved_memory_bytes -= reserved_memory_bytes_;
  record.descriptor.available_memory_bytes += reserved_memory_bytes_;

  if (succeeded) {
    apply_success(record, state->policy, latency_ms);
  } else if (failed) {
    apply_failure(record, state->policy);
  }
  return true;
}

WorkerRegistry::WorkerRegistry(const HealthPolicy policy)
    : state_(std::make_shared<detail::WorkerRegistryState>(policy)) {
  if (state_->policy.failures_before_degraded == 0U ||
      state_->policy.failures_before_failed == 0U ||
      state_->policy.successes_before_recovery == 0U) {
    throw std::invalid_argument("health policy thresholds must be positive");
  }
  if (state_->policy.failures_before_degraded > state_->policy.failures_before_failed) {
    throw std::invalid_argument("degradation threshold must not exceed failure threshold");
  }
}

void WorkerRegistry::upsert(WorkerDescriptor worker) {
  if (worker.id.empty() || worker.device.id.empty() || worker.device.name.empty()) {
    throw std::invalid_argument("worker and device identifiers must not be empty");
  }
  if (worker.load.max_active_requests == 0U || worker.load.queue_capacity == 0U) {
    throw std::invalid_argument("worker load capacities must be positive");
  }
  if (worker.load.active_requests > worker.load.max_active_requests ||
      worker.load.queue_depth > worker.load.queue_capacity) {
    throw std::invalid_argument("worker load must not exceed its declared capacity");
  }
  if (worker.available_memory_bytes > worker.device.memory_bytes) {
    throw std::invalid_argument("available worker memory exceeds device memory");
  }
  if (worker.supported_models.empty()) {
    throw std::invalid_argument("worker must support at least one model");
  }

  sort_and_deduplicate(worker.device.capabilities);
  sort_and_deduplicate(worker.supported_models);
  const auto id = worker.id;
  std::scoped_lock lock(state_->mutex);
  const auto existing = state_->workers.find(id);
  if (existing == state_->workers.end()) {
    state_->workers.emplace(id, detail::WorkerRecord{std::move(worker)});
    return;
  }

  const auto& reservations = existing->second;
  if (worker.load.active_requests < reservations.active_reservations ||
      worker.load.queue_depth < reservations.queue_reservations ||
      reservations.reserved_memory_bytes > worker.device.memory_bytes ||
      worker.available_memory_bytes >
          worker.device.memory_bytes - reservations.reserved_memory_bytes) {
    throw std::invalid_argument("replacement worker would discard live reservations");
  }
  existing->second.descriptor = std::move(worker);
  existing->second.consecutive_failures = 0U;
  existing->second.consecutive_successes = 0U;
}

bool WorkerRegistry::update_load(const std::string_view worker_id, const WorkerLoad load,
                                 const std::uint64_t available_memory_bytes) {
  if (load.max_active_requests == 0U || load.queue_capacity == 0U ||
      load.active_requests > load.max_active_requests || load.queue_depth > load.queue_capacity) {
    throw std::invalid_argument("updated worker load has invalid capacity values");
  }

  std::scoped_lock lock(state_->mutex);
  const auto iterator = state_->workers.find(worker_id);
  if (iterator == state_->workers.end()) {
    return false;
  }
  if (available_memory_bytes > iterator->second.descriptor.device.memory_bytes) {
    throw std::invalid_argument("updated available memory exceeds device memory");
  }
  if (load.active_requests < iterator->second.active_reservations ||
      load.queue_depth < iterator->second.queue_reservations ||
      iterator->second.reserved_memory_bytes > iterator->second.descriptor.device.memory_bytes ||
      available_memory_bytes > iterator->second.descriptor.device.memory_bytes -
                                   iterator->second.reserved_memory_bytes) {
    throw std::invalid_argument("updated load would discard live reservations");
  }
  iterator->second.descriptor.load = load;
  iterator->second.descriptor.available_memory_bytes = available_memory_bytes;
  return true;
}

bool WorkerRegistry::set_draining(const std::string_view worker_id, const bool draining) {
  std::scoped_lock lock(state_->mutex);
  const auto iterator = state_->workers.find(worker_id);
  if (iterator == state_->workers.end() ||
      iterator->second.descriptor.health == WorkerHealth::failed) {
    return false;
  }
  iterator->second.descriptor.health =
      draining ? WorkerHealth::draining : WorkerHealth::healthy;
  if (!draining) {
    iterator->second.consecutive_failures = 0U;
    iterator->second.consecutive_successes = 0U;
  }
  return true;
}

bool WorkerRegistry::record_success(const std::string_view worker_id,
                                    const std::uint32_t latency_ms) {
  std::scoped_lock lock(state_->mutex);
  const auto iterator = state_->workers.find(worker_id);
  if (iterator == state_->workers.end()) {
    return false;
  }
  auto& record = iterator->second;
  if (record.descriptor.health == WorkerHealth::failed ||
      record.descriptor.health == WorkerHealth::draining) {
    return false;
  }
  apply_success(record, state_->policy, latency_ms);
  return true;
}

bool WorkerRegistry::record_failure(const std::string_view worker_id) {
  std::scoped_lock lock(state_->mutex);
  const auto iterator = state_->workers.find(worker_id);
  if (iterator == state_->workers.end()) {
    return false;
  }
  auto& record = iterator->second;
  if (record.descriptor.health == WorkerHealth::failed ||
      record.descriptor.health == WorkerHealth::draining) {
    return false;
  }

  apply_failure(record, state_->policy);
  return true;
}

std::optional<WorkerLease> WorkerRegistry::select_and_reserve(
    const SelectionRequest& request) {
  if (request.model_id.empty()) {
    throw std::invalid_argument("selection model identifier must not be empty");
  }

  auto required_capabilities = request.required_capabilities;
  sort_and_deduplicate(required_capabilities);
  std::scoped_lock lock(state_->mutex);
  std::optional<SelectionDecision> best;
  for (const auto& [worker_id, record] : state_->workers) {
    const auto& worker = record.descriptor;
    if (worker.health == WorkerHealth::failed || worker.health == WorkerHealth::draining) {
      continue;
    }
    if (worker.load.active_requests >= worker.load.max_active_requests &&
        worker.load.queue_depth >= worker.load.queue_capacity) {
      continue;
    }
    if (worker.available_memory_bytes < request.required_memory_bytes ||
        !contains_all(worker.device.capabilities, required_capabilities) ||
        !std::binary_search(worker.supported_models.begin(), worker.supported_models.end(),
                            request.model_id)) {
      continue;
    }

    const auto score = worker_score(worker, request.latency_tier);
    if (!best.has_value() || score < best->score ||
        (score == best->score && worker_id < best->worker.id) ||
        (score == best->score && worker_id == best->worker.id &&
         worker.device.id < best->worker.device.id)) {
      best = SelectionDecision{worker, score};
    }
  }
  if (!best.has_value()) {
    return std::nullopt;
  }

  auto& record = state_->workers.at(best->worker.id);
  const bool active_slot =
      record.descriptor.load.active_requests < record.descriptor.load.max_active_requests;
  WorkerLease lease(state_, record.descriptor.id, active_slot, request.required_memory_bytes,
                    std::move(*best));
  if (active_slot) {
    ++record.descriptor.load.active_requests;
    ++record.active_reservations;
    ++lease.decision_.worker.load.active_requests;
  } else {
    ++record.descriptor.load.queue_depth;
    ++record.queue_reservations;
    ++lease.decision_.worker.load.queue_depth;
  }
  record.descriptor.available_memory_bytes -= request.required_memory_bytes;
  record.reserved_memory_bytes += request.required_memory_bytes;
  lease.decision_.worker.available_memory_bytes -= request.required_memory_bytes;
  return lease;
}

std::vector<WorkerDescriptor> WorkerRegistry::snapshot() const {
  std::scoped_lock lock(state_->mutex);
  std::vector<WorkerDescriptor> result;
  result.reserve(state_->workers.size());
  for (const auto& [worker_id, record] : state_->workers) {
    static_cast<void>(worker_id);
    result.push_back(record.descriptor);
  }
  return result;
}

RequestHandle::RequestHandle(std::string request_id,
                             std::shared_ptr<const std::uint8_t> owner_token)
    : request_id_(std::move(request_id)), owner_token_(std::move(owner_token)) {}

const std::string& RequestHandle::request_id() const noexcept { return request_id_; }

RequestState RequestHandle::state() const noexcept {
  return state_.load(std::memory_order_acquire);
}

bool RequestHandle::cancellation_requested() const noexcept {
  const auto current = state();
  return current == RequestState::cancel_requested || current == RequestState::cancelled;
}

bool AdmissionResult::accepted() const noexcept {
  return status == AdmissionStatus::accepted || status == AdmissionStatus::accepted_after_drop;
}

BoundedRequestQueue::BoundedRequestQueue(const std::size_t capacity,
                                         const OverflowPolicy overflow_policy)
    : capacity_(capacity),
      overflow_policy_(overflow_policy),
      owner_token_(std::make_shared<const std::uint8_t>(0U)) {
  if (capacity_ == 0U) {
    throw std::invalid_argument("queue capacity must be positive");
  }
  queue_.reserve(capacity_);
}

AdmissionResult BoundedRequestQueue::submit(RequestEnvelope request) {
  if (request.request_id.empty() || request.model_id.empty()) {
    throw std::invalid_argument("request and model identifiers must not be empty");
  }

  auto handle =
      std::shared_ptr<RequestHandle>(new RequestHandle(request.request_id, owner_token_));
  std::scoped_lock lock(mutex_);
  if (queue_.size() < capacity_) {
    queue_.push_back(QueuedRequest{std::move(request), handle});
    return AdmissionResult{AdmissionStatus::accepted, std::move(handle), std::nullopt};
  }

  if (overflow_policy_ == OverflowPolicy::reject_newest) {
    handle->state_.store(RequestState::rejected, std::memory_order_release);
    return AdmissionResult{AdmissionStatus::rejected_full, std::move(handle), std::nullopt};
  }

  auto dropped = std::move(queue_.front());
  queue_.erase(queue_.begin());
  dropped.handle->state_.store(RequestState::dropped, std::memory_order_release);
  const auto dropped_request_id = dropped.request.request_id;
  queue_.push_back(QueuedRequest{std::move(request), handle});
  return AdmissionResult{AdmissionStatus::accepted_after_drop, std::move(handle),
                         dropped_request_id};
}

bool BoundedRequestQueue::cancel(const std::shared_ptr<RequestHandle>& handle) {
  if (!handle || handle->owner_token_ != owner_token_) {
    return false;
  }

  std::scoped_lock lock(mutex_);
  const auto iterator = std::find_if(
      queue_.begin(), queue_.end(), [&handle](const auto& item) { return item.handle == handle; });
  if (iterator != queue_.end()) {
    auto expected = RequestState::queued;
    if (!handle->state_.compare_exchange_strong(expected, RequestState::cancelled,
                                                std::memory_order_acq_rel)) {
      return false;
    }
    queue_.erase(iterator);
    return true;
  }

  auto expected = RequestState::dispatched;
  if (handle->state_.compare_exchange_strong(expected, RequestState::cancel_requested,
                                             std::memory_order_acq_rel)) {
    return true;
  }
  return expected == RequestState::cancel_requested;
}

std::optional<QueuedRequest> BoundedRequestQueue::try_pop() {
  std::scoped_lock lock(mutex_);
  if (queue_.empty()) {
    return std::nullopt;
  }

  auto item = std::move(queue_.front());
  queue_.erase(queue_.begin());
  auto expected = RequestState::queued;
  if (!item.handle->state_.compare_exchange_strong(expected, RequestState::dispatched,
                                                   std::memory_order_acq_rel)) {
    throw std::logic_error("queued request had an invalid state transition");
  }
  return item;
}

bool BoundedRequestQueue::complete(const std::shared_ptr<RequestHandle>& handle) {
  if (!handle || handle->owner_token_ != owner_token_) {
    return false;
  }

  auto expected = RequestState::dispatched;
  if (handle->state_.compare_exchange_strong(expected, RequestState::completed,
                                             std::memory_order_acq_rel)) {
    return true;
  }
  if (expected == RequestState::cancel_requested) {
    return handle->state_.compare_exchange_strong(expected, RequestState::cancelled,
                                                  std::memory_order_acq_rel);
  }
  return false;
}

std::size_t BoundedRequestQueue::size() const {
  std::scoped_lock lock(mutex_);
  return queue_.size();
}

std::size_t BoundedRequestQueue::capacity() const noexcept { return capacity_; }

std::string_view to_string(const DeviceCapability value) noexcept {
  switch (value) {
    case DeviceCapability::cpu:
      return "cpu";
    case DeviceCapability::cuda:
      return "cuda";
    case DeviceCapability::tensor_acceleration:
      return "tensor_acceleration";
    case DeviceCapability::unified_memory:
      return "unified_memory";
  }
  return "unknown";
}

std::string_view to_string(const WorkerHealth value) noexcept {
  switch (value) {
    case WorkerHealth::healthy:
      return "healthy";
    case WorkerHealth::degraded:
      return "degraded";
    case WorkerHealth::draining:
      return "draining";
    case WorkerHealth::failed:
      return "failed";
  }
  return "unknown";
}

std::string_view to_string(const LatencyTier value) noexcept {
  switch (value) {
    case LatencyTier::interactive:
      return "interactive";
    case LatencyTier::balanced:
      return "balanced";
    case LatencyTier::batch:
      return "batch";
  }
  return "unknown";
}

std::string_view to_string(const RequestState value) noexcept {
  switch (value) {
    case RequestState::queued:
      return "queued";
    case RequestState::dispatched:
      return "dispatched";
    case RequestState::cancel_requested:
      return "cancel_requested";
    case RequestState::cancelled:
      return "cancelled";
    case RequestState::completed:
      return "completed";
    case RequestState::dropped:
      return "dropped";
    case RequestState::rejected:
      return "rejected";
  }
  return "unknown";
}

std::string_view to_string(const AdmissionStatus value) noexcept {
  switch (value) {
    case AdmissionStatus::accepted:
      return "accepted";
    case AdmissionStatus::accepted_after_drop:
      return "accepted_after_drop";
    case AdmissionStatus::rejected_full:
      return "rejected_full";
  }
  return "unknown";
}

}  // namespace conceptflow::native
