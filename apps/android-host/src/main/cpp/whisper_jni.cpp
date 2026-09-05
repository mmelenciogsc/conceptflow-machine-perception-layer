// SPDX-License-Identifier: MIT OR Apache-2.0
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

constexpr int kWhisperSampleRate = 16000;

struct WhisperRuntime {
    whisper_context * whisper = nullptr;
    whisper_vad_context * vad = nullptr;
    int threads = 1;
    std::mutex mutex;

    ~WhisperRuntime() {
        whisper_vad_free(vad);
        whisper_free(whisper);
    }
};

struct TranscriptionDeadline {
    std::chrono::steady_clock::time_point deadline;
    std::atomic_bool timed_out{false};
};

bool abort_at_deadline(void * opaque) {
    auto * state = static_cast<TranscriptionDeadline *>(opaque);
    if (std::chrono::steady_clock::now() < state->deadline) return false;
    state->timed_out.store(true, std::memory_order_relaxed);
    return true;
}

void discard_log(ggml_log_level, const char *, void *) {}

void throw_state(JNIEnv * env, const char * message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) {
        env->ThrowNew(type, message);
    }
}

std::string copy_utf8(JNIEnv * env, jstring value) {
    const char * raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) return {};
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

WhisperRuntime * from_handle(JNIEnv * env, jlong handle) {
    auto * runtime = reinterpret_cast<WhisperRuntime *>(static_cast<intptr_t>(handle));
    if (runtime == nullptr) throw_state(env, "Whisper runtime is closed");
    return runtime;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_conceptflow_mpl_host_speech_NativeWhisperBridge_create(
    JNIEnv * env,
    jobject,
    jstring model_path,
    jstring vad_model_path,
    jint threads
) {
    if (model_path == nullptr || vad_model_path == nullptr || threads < 1 || threads > 8) {
        throw_state(env, "Invalid Whisper initialization parameters");
        return 0;
    }
    const std::string model = copy_utf8(env, model_path);
    const std::string vad_model = copy_utf8(env, vad_model_path);
    if (env->ExceptionCheck()) return 0;

    whisper_log_set(discard_log, nullptr);
    auto * runtime = new (std::nothrow) WhisperRuntime();
    if (runtime == nullptr) {
        throw_state(env, "Whisper runtime allocation failed");
        return 0;
    }
    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    context_params.flash_attn = false;
    runtime->whisper = whisper_init_from_file_with_params(model.c_str(), context_params);
    whisper_vad_context_params vad_params = whisper_vad_default_context_params();
    vad_params.n_threads = threads;
    vad_params.use_gpu = false;
    runtime->vad = whisper_vad_init_from_file_with_params(vad_model.c_str(), vad_params);
    runtime->threads = threads;
    if (runtime->whisper == nullptr || runtime->vad == nullptr) {
        delete runtime;
        throw_state(env, "Whisper or Silero VAD model initialization failed");
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(runtime));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_conceptflow_mpl_host_speech_NativeWhisperBridge_transcribe(
    JNIEnv * env,
    jobject,
    jlong handle,
    jfloatArray samples,
    jlong timeout_millis
) {
    WhisperRuntime * runtime = from_handle(env, handle);
    if (runtime == nullptr || env->ExceptionCheck()) return nullptr;
    if (samples == nullptr || timeout_millis < 1000 || timeout_millis > 60000) {
        throw_state(env, "Invalid Whisper transcription parameters");
        return nullptr;
    }
    const jsize count = env->GetArrayLength(samples);
    if (count < kWhisperSampleRate / 4 || count > kWhisperSampleRate * 10) {
        throw_state(env, "Whisper input must contain 0.25 to 10 seconds of mono 16 kHz audio");
        return nullptr;
    }
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());
    if (env->ExceptionCheck()) {
        std::fill(pcm.begin(), pcm.end(), 0.0F);
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(runtime->mutex);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = runtime->threads;
    params.translate = false;
    params.language = "en";
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = true;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.temperature = 0.0F;
    params.max_tokens = 64;
    params.greedy.best_of = 1;
    TranscriptionDeadline deadline{
        std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_millis),
    };
    params.abort_callback = abort_at_deadline;
    params.abort_callback_user_data = &deadline;
    whisper_reset_timings(runtime->whisper);
    const int result = whisper_full(
        runtime->whisper,
        params,
        pcm.data(),
        count
    );
    std::fill(pcm.begin(), pcm.end(), 0.0F);
    if (result != 0) {
        if (deadline.timed_out.load(std::memory_order_relaxed)) return nullptr;
        throw_state(env, "Whisper transcription failed");
        return nullptr;
    }

    std::string transcript;
    const int text_segments = whisper_full_n_segments(runtime->whisper);
    for (int index = 0; index < text_segments; ++index) {
        const char * text = whisper_full_get_segment_text(runtime->whisper, index);
        if (text != nullptr) transcript.append(text);
    }
    jbyteArray encoded = env->NewByteArray(static_cast<jsize>(transcript.size()));
    if (encoded != nullptr && !transcript.empty()) {
        env->SetByteArrayRegion(
            encoded,
            0,
            static_cast<jsize>(transcript.size()),
            reinterpret_cast<const jbyte *>(transcript.data())
        );
    }
    std::fill(transcript.begin(), transcript.end(), '\0');
    return encoded;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_conceptflow_mpl_host_speech_NativeWhisperBridge_detectSpeech(
    JNIEnv * env,
    jobject,
    jlong handle,
    jfloatArray samples,
    jfloat threshold
) {
    WhisperRuntime * runtime = from_handle(env, handle);
    if (runtime == nullptr || env->ExceptionCheck()) return JNI_FALSE;
    if (samples == nullptr || threshold < 0.1F || threshold > 0.95F) {
        throw_state(env, "Invalid Silero VAD parameters");
        return JNI_FALSE;
    }
    const jsize count = env->GetArrayLength(samples);
    if (count < kWhisperSampleRate / 4 || count > kWhisperSampleRate * 10) {
        throw_state(env, "Silero VAD input must contain 0.25 to 10 seconds of mono 16 kHz audio");
        return JNI_FALSE;
    }
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());
    if (env->ExceptionCheck()) {
        std::fill(pcm.begin(), pcm.end(), 0.0F);
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(runtime->mutex);
    whisper_vad_params params = whisper_vad_default_params();
    params.threshold = threshold;
    params.min_speech_duration_ms = 250;
    params.min_silence_duration_ms = 180;
    params.max_speech_duration_s = 10.0F;
    params.speech_pad_ms = 160;
    params.samples_overlap = 0.10F;
    whisper_vad_segments * segments = whisper_vad_segments_from_samples(
        runtime->vad,
        params,
        pcm.data(),
        count
    );
    std::fill(pcm.begin(), pcm.end(), 0.0F);
    if (segments == nullptr) {
        throw_state(env, "Silero VAD execution failed");
        return JNI_FALSE;
    }
    const bool detected = whisper_vad_segments_n_segments(segments) > 0;
    whisper_vad_free_segments(segments);
    return detected ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_conceptflow_mpl_host_speech_NativeWhisperBridge_destroy(
    JNIEnv *,
    jobject,
    jlong handle
) {
    auto * runtime = reinterpret_cast<WhisperRuntime *>(static_cast<intptr_t>(handle));
    delete runtime;
}
