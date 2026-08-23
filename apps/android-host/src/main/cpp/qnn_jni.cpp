// SPDX-License-Identifier: MIT OR Apache-2.0
// This file uses the public QNN API but contains no copied Qualcomm implementation code.

#include <jni.h>

#include <QnnInterface.h>
#include <QnnModel.hpp>
#include <QnnTypeMacros.hpp>

#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

enum class Failure : jint {
  kInvalidArgument = 2,
  kRuntimeLoadFailed = 4,
  kProviderUnavailable = 5,
  kBackendInitializationFailed = 6,
  kDeviceInitializationFailed = 7,
  kContextInitializationFailed = 8,
  kModelComposeFailed = 9,
  kGraphFinalizeFailed = 10,
  kTensorSchemaMismatch = 11,
  kGraphExecutionFailed = 12,
  kSessionClosed = 13,
  kInternalError = 18,
};

thread_local Failure last_failure = Failure::kInternalError;
thread_local std::string last_message = "native QNN call has not completed";

constexpr size_t kMaximumLoaderDetailBytes = 384;

void fail(Failure failure, std::string message) {
  last_failure = failure;
  last_message = std::move(message);
  __android_log_print(ANDROID_LOG_ERROR, "ConceptFlowQNN", "%s", last_message.c_str());
}

std::string jstringValue(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) return {};
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

std::string joinPath(const std::string& directory, const char* name) {
  return directory + (directory.empty() || directory.back() == '/' ? "" : "/") + name;
}

std::string boundedLoaderDetail(const char* loader_error) {
  if (loader_error == nullptr || loader_error[0] == '\0') return {};
  std::string detail(loader_error);
  if (detail.size() > kMaximumLoaderDetailBytes) {
    detail.resize(kMaximumLoaderDetailBytes - 3);
    detail += "...";
  }
  return detail;
}

class DynamicLibrary final {
 public:
  DynamicLibrary() = default;
  ~DynamicLibrary() { reset(); }

  DynamicLibrary(const DynamicLibrary&) = delete;
  DynamicLibrary& operator=(const DynamicLibrary&) = delete;

  bool open(const std::string& path, int flags, const char* description) {
    if (handle_ != nullptr) {
      fail(Failure::kInternalError, "dynamic library handle was reused");
      return false;
    }
    dlerror();
    handle_ = dlopen(path.c_str(), flags);
    if (handle_ != nullptr) return true;

    std::string message = "dynamic library load failed for ";
    message += description;
    const std::string detail = boundedLoaderDetail(dlerror());
    if (!detail.empty()) {
      message += ": ";
      message += detail;
    }
    fail(Failure::kRuntimeLoadFailed, std::move(message));
    return false;
  }

  void* get() const { return handle_; }

 private:
  void reset() {
    if (handle_ != nullptr) dlclose(handle_);
    handle_ = nullptr;
  }

  void* handle_ = nullptr;
};

bool openRequiredLibrary(
    DynamicLibrary& library,
    const std::string& path,
    int flags,
    const char* description) {
  return library.open(path, flags, description);
}

struct EnvironmentRestore {
  bool had_value;
  std::string value;
  bool restore_on_exit = false;

  ~EnvironmentRestore() {
    if (!restore_on_exit) return;
    const int status = had_value ? setenv("ADSP_LIBRARY_PATH", value.c_str(), 1)
                                 : unsetenv("ADSP_LIBRARY_PATH");
    if (status != 0) {
      __android_log_print(ANDROID_LOG_ERROR, "ConceptFlowQNN",
                          "failed to restore ADSP_LIBRARY_PATH after initialization failure");
    }
  }
};

uint16_t floatToHalf(float value) {
  uint32_t bits;
  std::memcpy(&bits, &value, sizeof(bits));
  const uint32_t sign = (bits >> 16U) & 0x8000U;
  int32_t exponent = static_cast<int32_t>((bits >> 23U) & 0xffU) - 127 + 15;
  uint32_t mantissa = bits & 0x7fffffU;
  if (exponent <= 0) {
    if (exponent < -10) return static_cast<uint16_t>(sign);
    mantissa = (mantissa | 0x800000U) >> static_cast<uint32_t>(1 - exponent);
    return static_cast<uint16_t>(sign | ((mantissa + 0x1000U) >> 13U));
  }
  if (exponent >= 31) {
    return static_cast<uint16_t>(sign | (mantissa == 0 ? 0x7c00U : 0x7e00U));
  }
  mantissa += 0x1000U;
  if ((mantissa & 0x800000U) != 0) {
    mantissa = 0;
    if (++exponent >= 31) return static_cast<uint16_t>(sign | 0x7c00U);
  }
  return static_cast<uint16_t>(sign | (static_cast<uint32_t>(exponent) << 10U) | (mantissa >> 13U));
}

float halfToFloat(uint16_t half) {
  const uint32_t sign = static_cast<uint32_t>(half & 0x8000U) << 16U;
  uint32_t exponent = (half >> 10U) & 0x1fU;
  uint32_t mantissa = half & 0x3ffU;
  uint32_t bits;
  if (exponent == 0) {
    if (mantissa == 0) {
      bits = sign;
    } else {
      uint32_t shift = 0;
      while ((mantissa & 0x400U) == 0) {
        mantissa <<= 1U;
        ++shift;
      }
      bits = sign | ((127U - 14U - shift) << 23U) | ((mantissa & 0x3ffU) << 13U);
    }
  } else if (exponent == 31) {
    bits = sign | 0x7f800000U | (mantissa << 13U);
  } else {
    bits = sign | ((exponent - 15U + 127U) << 23U) | (mantissa << 13U);
  }
  float value;
  std::memcpy(&value, &bits, sizeof(value));
  return value;
}

using ComposeGraphsFn = qnn_wrapper_api::ModelError_t (*)(
    Qnn_BackendHandle_t,
    QNN_INTERFACE_VER_TYPE,
    Qnn_ContextHandle_t,
    const qnn_wrapper_api::GraphConfigInfo_t**,
    uint32_t,
    qnn_wrapper_api::GraphInfo_t***,
    uint32_t*,
    bool,
    QnnLog_Callback_t,
    QnnLog_Level_t);
using FreeGraphsFn = qnn_wrapper_api::ModelError_t (*)(qnn_wrapper_api::GraphInfo_t***, uint32_t);
using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);

struct ExpectedTensor {
  const char* name;
  std::vector<uint32_t> dimensions;
};

struct Session {
  std::mutex execution_mutex;
  // Declared in dependency order so reverse member destruction unloads the model and QNN
  // libraries before the platform RPC transport they use.
  DynamicLibrary platform_rpc_library;
  DynamicLibrary system_library;
  DynamicLibrary prepare_library;
  DynamicLibrary stub_library;
  DynamicLibrary backend_library;
  DynamicLibrary model_library;
  QNN_INTERFACE_VER_TYPE api{};
  Qnn_BackendHandle_t backend = nullptr;
  Qnn_DeviceHandle_t device = nullptr;
  Qnn_ContextHandle_t context = nullptr;
  qnn_wrapper_api::GraphInfo_t** graph_info = nullptr;
  uint32_t graph_count = 0;
  FreeGraphsFn free_graphs = nullptr;
  std::vector<uint16_t> input;
  std::vector<std::vector<uint16_t>> outputs;

  ~Session() {
    if (graph_info != nullptr && free_graphs != nullptr) free_graphs(&graph_info, graph_count);
    graph_info = nullptr;
    if (context != nullptr && api.contextFree != nullptr) api.contextFree(context, nullptr);
    context = nullptr;
    if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
    device = nullptr;
    if (backend != nullptr && api.backendFree != nullptr) api.backendFree(backend);
    backend = nullptr;
  }
};

std::mutex sessions_mutex;
// QNN initialization temporarily updates the process-wide DSP library search path. Keep the
// environment update and all dependent dlopen/provider setup in one critical section.
std::mutex initialization_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
std::atomic<jlong> next_handle{1};

bool dimensionsEqual(const Qnn_Tensor_t& tensor, const std::vector<uint32_t>& expected) {
  if (QNN_TENSOR_GET_RANK(tensor) != expected.size()) return false;
  const uint32_t* actual = QNN_TENSOR_GET_DIMENSIONS(tensor);
  if (actual == nullptr) return false;
  return std::equal(expected.begin(), expected.end(), actual);
}

bool tensorMatches(const Qnn_Tensor_t& tensor, const ExpectedTensor& expected) {
  const char* name = QNN_TENSOR_GET_NAME(tensor);
  return name != nullptr && expected.name == std::string(name) &&
      QNN_TENSOR_GET_DATA_TYPE(tensor) == QNN_DATATYPE_FLOAT_16 &&
      dimensionsEqual(tensor, expected.dimensions);
}

uint64_t elementCount(const Qnn_Tensor_t& tensor) {
  const uint32_t rank = QNN_TENSOR_GET_RANK(tensor);
  const uint32_t* dimensions = QNN_TENSOR_GET_DIMENSIONS(tensor);
  if (rank == 0 || rank > 4 || dimensions == nullptr) return 0;
  uint64_t count = 1;
  for (uint32_t index = 0; index < rank; ++index) {
    if (dimensions[index] == 0 || count > 16U * 1024U * 1024U / dimensions[index]) return 0;
    count *= dimensions[index];
  }
  return count;
}

bool setBuffer(Qnn_Tensor_t* tensor, void* data, size_t size) {
  if (tensor == nullptr || data == nullptr || size == 0 || size > std::numeric_limits<uint32_t>::max()) return false;
  QNN_TENSOR_SET_MEM_TYPE(tensor, QNN_TENSORMEMTYPE_RAW);
  Qnn_ClientBuffer_t buffer = QNN_CLIENT_BUFFER_INIT;
  buffer.data = data;
  buffer.dataSize = static_cast<uint32_t>(size);
  QNN_TENSOR_SET_CLIENT_BUF(tensor, buffer);
  return true;
}

bool validateAndAllocate(Session& session, jint model_kind) {
  if (session.graph_count != 1 || session.graph_info == nullptr || session.graph_info[0] == nullptr) {
    fail(Failure::kTensorSchemaMismatch, "model must expose exactly one graph");
    return false;
  }
  auto& graph = *session.graph_info[0];
  const ExpectedTensor input_expected = model_kind == 0
      ? ExpectedTensor{"images", {1, 640, 640, 3}}
      : ExpectedTensor{"images", {1, 392, 392, 3}};
  const std::vector<ExpectedTensor> output_expected = model_kind == 0
      ? std::vector<ExpectedTensor>{{"output0", {1, 300, 38}}, {"output1", {1, 160, 160, 32}}}
      : std::vector<ExpectedTensor>{{"depth_meters", {1, 392, 392}}};
  if (graph.numInputTensors != 1 || graph.numOutputTensors != output_expected.size() ||
      !tensorMatches(graph.inputTensors[0], input_expected)) {
    fail(Failure::kTensorSchemaMismatch, "input/output count, FP16 type, name, or NHWC dimensions differ");
    return false;
  }
  const uint64_t input_elements = elementCount(graph.inputTensors[0]);
  if (input_elements == 0) {
    fail(Failure::kTensorSchemaMismatch, "invalid input element count");
    return false;
  }
  session.input.resize(input_elements);
  if (!setBuffer(&graph.inputTensors[0], session.input.data(), session.input.size() * sizeof(uint16_t))) {
    fail(Failure::kTensorSchemaMismatch, "could not attach bounded input buffer");
    return false;
  }
  session.outputs.resize(output_expected.size());
  for (size_t index = 0; index < output_expected.size(); ++index) {
    if (!tensorMatches(graph.outputTensors[index], output_expected[index])) {
      fail(Failure::kTensorSchemaMismatch, "output FP16 type, name, or dimensions differ");
      return false;
    }
    const uint64_t output_elements = elementCount(graph.outputTensors[index]);
    if (output_elements == 0) {
      fail(Failure::kTensorSchemaMismatch, "invalid output element count");
      return false;
    }
    session.outputs[index].resize(output_elements);
    if (!setBuffer(&graph.outputTensors[index], session.outputs[index].data(),
                   session.outputs[index].size() * sizeof(uint16_t))) {
      fail(Failure::kTensorSchemaMismatch, "could not attach bounded output buffer");
      return false;
    }
  }
  return true;
}

std::shared_ptr<Session> findSession(jlong handle) {
  std::lock_guard<std::mutex> guard(sessions_mutex);
  auto iterator = sessions.find(handle);
  return iterator == sessions.end() ? nullptr : iterator->second;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_conceptflow_mpl_host_vision_QnnNativeBridge_open(
    JNIEnv* env, jobject, jstring runtime_directory_value, jstring model_path_value, jint model_kind) {
  const std::string runtime_directory = jstringValue(env, runtime_directory_value);
  const std::string model_path = jstringValue(env, model_path_value);
  if (runtime_directory.empty() || model_path.empty() || (model_kind != 0 && model_kind != 1)) {
    fail(Failure::kInvalidArgument, "invalid runtime/model path or model kind");
    return 0;
  }
  std::lock_guard<std::mutex> initialization_guard(initialization_mutex);
  const char* existing_adsp_path = getenv("ADSP_LIBRARY_PATH");
  EnvironmentRestore adsp_restore{
      existing_adsp_path != nullptr,
      existing_adsp_path == nullptr ? "" : existing_adsp_path,
  };
  // On a failed initialization the session must release QNN resources before the prior DSP
  // search path is restored. Local variables are destroyed in reverse construction order.
  auto session = std::make_shared<Session>();
  std::string adsp_path = runtime_directory;
  if (existing_adsp_path != nullptr && existing_adsp_path[0] != '\0') {
    adsp_path += ";";
    adsp_path += existing_adsp_path;
  }
  if (setenv("ADSP_LIBRARY_PATH", adsp_path.c_str(), 1) != 0) {
    fail(Failure::kRuntimeLoadFailed, "could not select the verified V79 skel directory");
    return 0;
  }
  adsp_restore.restore_on_exit = true;
  const char* configured_adsp_path = getenv("ADSP_LIBRARY_PATH");
  if (configured_adsp_path == nullptr || adsp_path != configured_adsp_path) {
    fail(Failure::kRuntimeLoadFailed, "verified V79 skel directory was not retained");
    return 0;
  }
  // Android 12+ exposes declared vendor public libraries through the application linker
  // namespace. Load the platform copy by SONAME; never resolve or copy it from runtime_directory.
  if (!openRequiredLibrary(session->platform_rpc_library, "libcdsprpc.so",
                           RTLD_NOW | RTLD_GLOBAL, "platform public libcdsprpc.so")) {
    return 0;
  }
  if (!openRequiredLibrary(session->system_library,
                           joinPath(runtime_directory, "libQnnSystem.so"),
                           RTLD_NOW | RTLD_LOCAL, "private QNN system runtime")) {
    return 0;
  }
  if (!openRequiredLibrary(session->prepare_library,
                           joinPath(runtime_directory, "libQnnHtpPrepare.so"),
                           RTLD_NOW | RTLD_GLOBAL, "private QNN HTP prepare runtime")) {
    return 0;
  }
  if (!openRequiredLibrary(session->stub_library,
                           joinPath(runtime_directory, "libQnnHtpV79Stub.so"),
                           RTLD_NOW | RTLD_GLOBAL, "private QNN V79 stub")) {
    return 0;
  }
  if (!openRequiredLibrary(session->backend_library,
                           joinPath(runtime_directory, "libQnnHtp.so"),
                           RTLD_NOW | RTLD_LOCAL, "private QNN HTP backend")) {
    return 0;
  }
  auto get_providers = reinterpret_cast<GetProvidersFn>(
      dlsym(session->backend_library.get(), "QnnInterface_getProviders"));
  const QnnInterface_t** providers = nullptr;
  uint32_t provider_count = 0;
  if (get_providers == nullptr || get_providers(&providers, &provider_count) != QNN_SUCCESS ||
      providers == nullptr || provider_count == 0) {
    fail(Failure::kProviderUnavailable, "QNN provider discovery failed");
    return 0;
  }
  bool provider_found = false;
  for (uint32_t index = 0; index < provider_count; ++index) {
    if (providers[index] != nullptr &&
        providers[index]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR &&
        providers[index]->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
      session->api = providers[index]->QNN_INTERFACE_VER_NAME;
      provider_found = true;
      break;
    }
  }
  if (!provider_found) {
    fail(Failure::kProviderUnavailable, "no compatible QNN 2.37 provider in QAIRT 2.48 runtime");
    return 0;
  }
  if (session->api.backendCreate == nullptr ||
      session->api.backendCreate(nullptr, nullptr, &session->backend) != QNN_BACKEND_NO_ERROR) {
    fail(Failure::kBackendInitializationFailed, "HTP backendCreate failed");
    return 0;
  }
  if (session->api.deviceCreate == nullptr ||
      session->api.deviceCreate(nullptr, nullptr, &session->device) != QNN_SUCCESS || session->device == nullptr) {
    fail(Failure::kDeviceInitializationFailed, "HTP deviceCreate failed or returned no device");
    return 0;
  }
  if (session->api.contextCreate == nullptr ||
      session->api.contextCreate(session->backend, session->device, nullptr, &session->context) != QNN_CONTEXT_NO_ERROR) {
    fail(Failure::kContextInitializationFailed, "HTP contextCreate failed");
    return 0;
  }
  if (!openRequiredLibrary(session->model_library, model_path, RTLD_NOW | RTLD_LOCAL,
                           "verified private QNN model")) {
    return 0;
  }
  auto compose = reinterpret_cast<ComposeGraphsFn>(
      dlsym(session->model_library.get(), "QnnModel_composeGraphs"));
  session->free_graphs = reinterpret_cast<FreeGraphsFn>(
      dlsym(session->model_library.get(), "QnnModel_freeGraphsInfo"));
  if (compose == nullptr || session->free_graphs == nullptr ||
      compose(session->backend, session->api, session->context, nullptr, 0, &session->graph_info,
              &session->graph_count, false, nullptr, QNN_LOG_LEVEL_ERROR) !=
          qnn_wrapper_api::ModelError_t::MODEL_NO_ERROR) {
    fail(Failure::kModelComposeFailed, "model compose failed");
    return 0;
  }
  if (!validateAndAllocate(*session, model_kind)) return 0;
  if (session->api.graphFinalize == nullptr ||
      session->api.graphFinalize(session->graph_info[0]->graph, nullptr, nullptr) != QNN_GRAPH_NO_ERROR) {
    fail(Failure::kGraphFinalizeFailed, "HTP graphFinalize failed");
    return 0;
  }
  const jlong handle = next_handle.fetch_add(1);
  {
    std::lock_guard<std::mutex> guard(sessions_mutex);
    sessions.emplace(handle, std::move(session));
  }
  adsp_restore.restore_on_exit = false;
  last_message = "ok";
  return handle;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_conceptflow_mpl_host_vision_QnnNativeBridge_execute(
    JNIEnv* env, jobject, jlong handle, jbyteArray input_value) {
  const auto session = findSession(handle);
  if (session == nullptr) {
    fail(Failure::kSessionClosed, "unknown or closed session");
    return nullptr;
  }
  std::lock_guard<std::mutex> execution_guard(session->execution_mutex);
  const jsize byte_count = input_value == nullptr ? 0 : env->GetArrayLength(input_value);
  const size_t expected_bytes = session->input.size() * sizeof(float);
  if (byte_count <= 0 || static_cast<size_t>(byte_count) != expected_bytes) {
    fail(Failure::kInvalidArgument, "FLOAT32 input byte count mismatch");
    return nullptr;
  }
  jboolean copied = JNI_FALSE;
  jbyte* bytes = env->GetByteArrayElements(input_value, &copied);
  if (bytes == nullptr) {
    fail(Failure::kInternalError, "could not access JNI input bytes");
    return nullptr;
  }
  bool finite = true;
  for (size_t index = 0; index < session->input.size(); ++index) {
    float value;
    std::memcpy(&value, bytes + index * sizeof(float), sizeof(value));
    if (!std::isfinite(value)) {
      finite = false;
      break;
    }
    session->input[index] = floatToHalf(value);
  }
  env->ReleaseByteArrayElements(input_value, bytes, JNI_ABORT);
  if (!finite) {
    fail(Failure::kInvalidArgument, "input contains non-finite FLOAT32 values");
    return nullptr;
  }
  auto& graph = *session->graph_info[0];
  if (session->api.graphExecute == nullptr ||
      session->api.graphExecute(graph.graph, graph.inputTensors, graph.numInputTensors,
                                graph.outputTensors, graph.numOutputTensors, nullptr, nullptr) !=
          QNN_GRAPH_NO_ERROR) {
    fail(Failure::kGraphExecutionFailed, "HTP graphExecute failed");
    return nullptr;
  }
  jclass byte_array_class = env->FindClass("[B");
  if (byte_array_class == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(session->outputs.size()), byte_array_class, nullptr);
  if (result == nullptr) return nullptr;
  for (size_t output_index = 0; output_index < session->outputs.size(); ++output_index) {
    const auto& half_output = session->outputs[output_index];
    std::vector<float> float_output(half_output.size());
    std::transform(half_output.begin(), half_output.end(), float_output.begin(), halfToFloat);
    const jsize output_bytes = static_cast<jsize>(float_output.size() * sizeof(float));
    jbyteArray output = env->NewByteArray(output_bytes);
    if (output == nullptr) return nullptr;
    env->SetByteArrayRegion(output, 0, output_bytes, reinterpret_cast<const jbyte*>(float_output.data()));
    env->SetObjectArrayElement(result, static_cast<jsize>(output_index), output);
    env->DeleteLocalRef(output);
  }
  last_message = "ok";
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_conceptflow_mpl_host_vision_QnnNativeBridge_close(JNIEnv*, jobject, jlong handle) {
  std::shared_ptr<Session> removed;
  {
    std::lock_guard<std::mutex> guard(sessions_mutex);
    const auto iterator = sessions.find(handle);
    if (iterator == sessions.end()) return;
    removed = std::move(iterator->second);
    sessions.erase(iterator);
  }
  std::lock_guard<std::mutex> execution_guard(removed->execution_mutex);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_conceptflow_mpl_host_vision_QnnNativeBridge_lastErrorCode(JNIEnv*, jobject) {
  return static_cast<jint>(last_failure);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_conceptflow_mpl_host_vision_QnnNativeBridge_lastErrorMessage(JNIEnv* env, jobject) {
  return env->NewStringUTF(last_message.c_str());
}
