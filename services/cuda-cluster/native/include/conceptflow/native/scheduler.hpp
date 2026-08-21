// SPDX-License-Identifier: MIT OR Apache-2.0
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace conceptflow::native {

namespace detail {
struct WorkerRegistryState;
}

enum class DeviceCapability : std::uint8_t {
  cpu,
  cuda,
  tensor_acceleration,
  unified_memory,
};

enum class WorkerHealth : std::uint8_t {
  healthy,
  degraded,
  draining,
  failed,
};

enum class LatencyTier : std::uint8_t {
  interactive,
  balanced,
  batch,
};

struct DeviceDescriptor {
  std::string id;
  std::string name;
  std::uint64_t memory_bytes{};
  std::vector<DeviceCapability> capabilities;
};

struct WorkerLoad {
  std::size_t active_requests{};
  std::size_t max_active_requests{1U};
  std::size_t queue_depth{};
  std::size_t queue_capacity{1U};
  std::uint32_t estimated_latency_ms{1U};
};

struct WorkerDescriptor {
  std::string id;
  DeviceDescriptor device;
  WorkerHealth health{WorkerHealth::healthy};
  WorkerLoad load;
  std::uint64_t available_memory_bytes{};
  std::vector<std::string> supported_models;
};

struct SelectionRequest {
  std::string model_id;
  std::uint64_t required_memory_bytes{};
  std::vector<DeviceCapability> required_capabilities;
  LatencyTier latency_tier{LatencyTier::balanced};
};

struct SelectionDecision {
  WorkerDescriptor worker;
  long double score{};
};

struct HealthPolicy {
  std::uint32_t failures_before_degraded{1U};
  std::uint32_t failures_before_failed{3U};
  std::uint32_t successes_before_recovery{2U};
};

class WorkerLease final {
 public:
  ~WorkerLease();

  WorkerLease(const WorkerLease&) = delete;
  WorkerLease& operator=(const WorkerLease&) = delete;
  WorkerLease(WorkerLease&& other) noexcept;
  WorkerLease& operator=(WorkerLease&& other) noexcept;

  [[nodiscard]] const SelectionDecision& decision() const noexcept;
  [[nodiscard]] const WorkerDescriptor& worker() const noexcept;
  [[nodiscard]] bool active() const noexcept;

  // Each terminal operation releases the reservation. Only the first terminal
  // operation succeeds; destruction cancels an active reservation. Success and
  // failure update health only while the worker accepts health transitions.
  [[nodiscard]] bool complete_success(std::uint32_t latency_ms) noexcept;
  [[nodiscard]] bool complete_failure() noexcept;
  [[nodiscard]] bool cancel() noexcept;

 private:
  friend class WorkerRegistry;

  WorkerLease(std::shared_ptr<detail::WorkerRegistryState> state, std::string worker_id,
              bool active_slot, std::uint64_t reserved_memory_bytes,
              SelectionDecision decision);
  [[nodiscard]] bool release(bool succeeded, bool failed,
                             std::uint32_t latency_ms) noexcept;

  std::shared_ptr<detail::WorkerRegistryState> state_;
  std::string worker_id_;
  bool active_slot_{};
  std::uint64_t reserved_memory_bytes_{};
  SelectionDecision decision_;
  bool active_{};
};

class WorkerRegistry final {
 public:
  explicit WorkerRegistry(HealthPolicy policy = {});

  WorkerRegistry(const WorkerRegistry&) = delete;
  WorkerRegistry& operator=(const WorkerRegistry&) = delete;
  WorkerRegistry(WorkerRegistry&&) = delete;
  WorkerRegistry& operator=(WorkerRegistry&&) = delete;

  void upsert(WorkerDescriptor worker);
  // Refreshed load values are totals and must retain all live lease reservations.
  [[nodiscard]] bool update_load(std::string_view worker_id, WorkerLoad load,
                                 std::uint64_t available_memory_bytes);
  [[nodiscard]] bool set_draining(std::string_view worker_id, bool draining = true);
  [[nodiscard]] bool record_success(std::string_view worker_id, std::uint32_t latency_ms);
  [[nodiscard]] bool record_failure(std::string_view worker_id);
  [[nodiscard]] std::optional<WorkerLease> select_and_reserve(
      const SelectionRequest& request);
  [[nodiscard]] std::vector<WorkerDescriptor> snapshot() const;

 private:
  std::shared_ptr<detail::WorkerRegistryState> state_;
};

enum class OverflowPolicy : std::uint8_t {
  reject_newest,
  drop_oldest,
};

enum class RequestState : std::uint8_t {
  queued,
  dispatched,
  cancel_requested,
  cancelled,
  completed,
  dropped,
  rejected,
};

struct RequestEnvelope {
  std::string request_id;
  std::string model_id;
  LatencyTier latency_tier{LatencyTier::balanced};
};

class RequestHandle final {
 public:
  [[nodiscard]] const std::string& request_id() const noexcept;
  [[nodiscard]] RequestState state() const noexcept;
  [[nodiscard]] bool cancellation_requested() const noexcept;

 private:
  friend class BoundedRequestQueue;

  RequestHandle(std::string request_id, std::shared_ptr<const std::uint8_t> owner_token);

  std::string request_id_;
  std::shared_ptr<const std::uint8_t> owner_token_;
  std::atomic<RequestState> state_{RequestState::queued};
};

enum class AdmissionStatus : std::uint8_t {
  accepted,
  accepted_after_drop,
  rejected_full,
};

struct AdmissionResult {
  AdmissionStatus status{AdmissionStatus::rejected_full};
  std::shared_ptr<RequestHandle> handle;
  std::optional<std::string> dropped_request_id;

  [[nodiscard]] bool accepted() const noexcept;
};

struct QueuedRequest {
  RequestEnvelope request;
  std::shared_ptr<RequestHandle> handle;
};

class BoundedRequestQueue final {
 public:
  BoundedRequestQueue(std::size_t capacity, OverflowPolicy overflow_policy);

  [[nodiscard]] AdmissionResult submit(RequestEnvelope request);
  [[nodiscard]] bool cancel(const std::shared_ptr<RequestHandle>& handle);
  [[nodiscard]] std::optional<QueuedRequest> try_pop();
  [[nodiscard]] bool complete(const std::shared_ptr<RequestHandle>& handle);
  [[nodiscard]] std::size_t size() const;
  [[nodiscard]] std::size_t capacity() const noexcept;

 private:
  std::size_t capacity_;
  OverflowPolicy overflow_policy_;
  std::shared_ptr<const std::uint8_t> owner_token_;
  mutable std::mutex mutex_;
  std::vector<QueuedRequest> queue_;
};

[[nodiscard]] std::string_view to_string(DeviceCapability value) noexcept;
[[nodiscard]] std::string_view to_string(WorkerHealth value) noexcept;
[[nodiscard]] std::string_view to_string(LatencyTier value) noexcept;
[[nodiscard]] std::string_view to_string(RequestState value) noexcept;
[[nodiscard]] std::string_view to_string(AdmissionStatus value) noexcept;

}  // namespace conceptflow::native
