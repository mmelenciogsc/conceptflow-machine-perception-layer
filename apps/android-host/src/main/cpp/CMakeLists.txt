# SPDX-License-Identifier: MIT OR Apache-2.0
cmake_minimum_required(VERSION 3.22.1)
project(conceptflow_qnn_jni LANGUAGES CXX)

if(NOT DEFINED QNN_SDK_ROOT OR NOT EXISTS "${QNN_SDK_ROOT}/include/QNN/QnnInterface.h")
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
