// SPDX-License-Identifier: MIT OR Apache-2.0

#include "conceptflow/native/scheduler.hpp"

#include <cstdint>
#include <exception>
#include <iostream>
#include <string>
#include <utility>

namespace cfn = conceptflow::native;

namespace {

constexpr std::uint64_t gibibytes(const std::uint64_t value) noexcept {
  return value * 1024U * 1024U * 1024U;
}

cfn::WorkerDescriptor synthetic_worker(std::string id, std::string device_id,
                                       const cfn::DeviceCapability capability,
                                       const std::uint64_t memory_bytes,
                                       const std::uint32_t latency_ms) {
  return cfn::WorkerDescriptor{
      .id = std::move(id),
      .device = cfn::DeviceDescriptor{.id = std::move(device_id),
                                      .name = "synthetic device",
                                      .memory_bytes = memory_bytes,
                                      .capabilities = {capability}},
      .health = cfn::WorkerHealth::healthy,
      .load = cfn::WorkerLoad{.active_requests = 0U,
                              .max_active_requests = 2U,
                              .queue_depth = 0U,
                              .queue_capacity = 4U,
                              .estimated_latency_ms = latency_ms},
      .available_memory_bytes = memory_bytes,
      .supported_models = {"synthetic-detector"},
  };
}

}  // namespace

int main() {
  try {
    cfn::WorkerRegistry registry;
    registry.upsert(synthetic_worker("cpu-worker", "cpu:synthetic", cfn::DeviceCapability::cpu,
                                     gibibytes(16U), 35U));
    registry.upsert(synthetic_worker("gpu-worker", "cuda:synthetic", cfn::DeviceCapability::cuda,
                                     gibibytes(8U), 12U));

    auto lease = registry.select_and_reserve(cfn::SelectionRequest{
        .model_id = "synthetic-detector",
        .required_memory_bytes = gibibytes(2U),
        .required_capabilities = {cfn::DeviceCapability::cuda},
        .latency_tier = cfn::LatencyTier::interactive,
    });
    if (!lease.has_value()) {
      std::cerr << "{\"event\":\"error\",\"message\":\"no synthetic worker selected\"}\n";
      return 1;
    }
    std::cout << "{\"event\":\"reservation\",\"worker\":\"" << lease->worker().id
              << "\",\"health\":\"" << cfn::to_string(lease->worker().health)
              << "\",\"tier\":\"interactive\",\"synthetic\":true}\n";

    cfn::BoundedRequestQueue queue(2U, cfn::OverflowPolicy::drop_oldest);
    const auto first = queue.submit({"request-1", "synthetic-detector", cfn::LatencyTier::batch});
    const auto second =
        queue.submit({"request-2", "synthetic-detector", cfn::LatencyTier::balanced});
    const auto third =
        queue.submit({"request-3", "synthetic-detector", cfn::LatencyTier::interactive});
    std::cout << "{\"event\":\"admission\",\"request\":\"request-3\",\"status\":\""
              << cfn::to_string(third.status) << "\",\"dropped\":\""
              << third.dropped_request_id.value_or("") << "\",\"depth\":" << queue.size()
              << "}\n";

    if (first.handle->state() != cfn::RequestState::dropped || !queue.cancel(second.handle)) {
      std::cerr << "{\"event\":\"error\",\"message\":\"queue invariant failed\"}\n";
      return 1;
    }
    std::cout << "{\"event\":\"cancellation\",\"request\":\"request-2\",\"state\":\""
              << cfn::to_string(second.handle->state()) << "\",\"depth\":" << queue.size()
              << "}\n";

    const auto work = queue.try_pop();
    if (!work.has_value() || !queue.complete(work->handle)) {
      std::cerr << "{\"event\":\"error\",\"message\":\"completion invariant failed\"}\n";
      return 1;
    }
    std::cout << "{\"event\":\"completion\",\"request\":\"" << work->request.request_id
              << "\",\"state\":\"" << cfn::to_string(work->handle->state()) << "\"}\n";
    if (!lease->complete_success(12U)) {
      std::cerr << "{\"event\":\"error\",\"message\":\"reservation release failed\"}\n";
      return 1;
    }
    std::cout << "{\"event\":\"release\",\"worker\":\"" << lease->worker().id
              << "\",\"active\":" << registry.snapshot().back().load.active_requests
              << "}\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "{\"event\":\"error\",\"message\":\"" << error.what() << "\"}\n";
    return 1;
  }
}
