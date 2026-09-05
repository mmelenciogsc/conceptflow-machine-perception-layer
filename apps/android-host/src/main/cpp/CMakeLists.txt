# SPDX-License-Identifier: MIT OR Apache-2.0
cmake_minimum_required(VERSION 3.22.1)
project(conceptflow_android_native LANGUAGES C CXX)

if(DEFINED QNN_SDK_ROOT)
    if(NOT EXISTS "${QNN_SDK_ROOT}/include/QNN/QnnInterface.h")
        message(FATAL_ERROR "QNN_SDK_ROOT must point to an externally installed QAIRT 2.48 SDK")
    endif()

    add_library(conceptflow_qnn_jni SHARED qnn_jni.cpp)
    target_compile_features(conceptflow_qnn_jni PRIVATE cxx_std_17)
    target_compile_options(conceptflow_qnn_jni PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
    target_include_directories(conceptflow_qnn_jni PRIVATE
        "${QNN_SDK_ROOT}/include/QNN"
        "${QNN_SDK_ROOT}/share/QNN/converter/jni"
        "${QNN_SDK_ROOT}/examples/QNN/SampleApp/SampleApp/src"
    )
    target_link_libraries(conceptflow_qnn_jni PRIVATE dl log)
endif()

if(DEFINED WHISPER_CPP_ROOT)
    if(NOT EXISTS "${WHISPER_CPP_ROOT}/include/whisper.h" OR
       NOT EXISTS "${WHISPER_CPP_ROOT}/ggml/CMakeLists.txt")
        message(FATAL_ERROR "WHISPER_CPP_ROOT must point to the pinned external whisper.cpp source tree")
    endif()

    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    # GenieX already packages libomp. Keep the independent CPU speech runtime free of a second
    # OpenMP runtime; whisper.cpp still uses its bounded native worker threads.
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    # Snapdragon 8 Elite reports FP16 vector arithmetic and DOTPROD. Keep i8mm out of the
    # baseline until the device benchmark proves that an ARMv8.6-only binary is worthwhile.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
    add_subdirectory("${WHISPER_CPP_ROOT}" "${CMAKE_BINARY_DIR}/whisper-cpp" EXCLUDE_FROM_ALL)

    # Android's debuggable variant otherwise leaves ggml at -O0, which made a ten-second
    # small.en window take minutes on the Poco. Preserve symbols while exercising optimized
    # kernels so debug physical tests represent the production CPU path.
    foreach(whisper_target whisper ggml ggml-base ggml-cpu)
        if(TARGET "${whisper_target}")
            target_compile_options("${whisper_target}" PRIVATE "$<$<CONFIG:Debug>:-O3>")
        endif()
    endforeach()

    add_library(conceptflow_whisper_jni SHARED whisper_jni.cpp)
    target_compile_features(conceptflow_whisper_jni PRIVATE cxx_std_17)
    target_compile_options(conceptflow_whisper_jni PRIVATE
        -Wall -Wextra -Werror -fvisibility=hidden -march=armv8.2-a+fp16+dotprod
        "$<$<CONFIG:Debug>:-O3>"
    )
    # whisper.cpp and ggml are linked statically into this one JNI boundary. Hide their symbols so
    # they cannot collide with independent ggml libraries packaged by optional Android runtimes.
    target_link_options(conceptflow_whisper_jni PRIVATE "-Wl,--exclude-libs,ALL")
    target_include_directories(conceptflow_whisper_jni PRIVATE "${WHISPER_CPP_ROOT}/include")
    target_link_libraries(conceptflow_whisper_jni PRIVATE whisper log)
endif()
