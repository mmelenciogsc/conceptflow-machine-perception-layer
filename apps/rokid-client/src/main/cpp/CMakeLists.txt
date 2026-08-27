# SPDX-License-Identifier: MIT OR Apache-2.0
cmake_minimum_required(VERSION 3.22.1)
project(conceptflow_yuv_converter LANGUAGES CXX)

add_library(conceptflow_yuv_core STATIC yuv_rgb_converter.cpp)
target_compile_features(conceptflow_yuv_core PUBLIC cxx_std_17)
target_compile_options(conceptflow_yuv_core PRIVATE
    -O3
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
)
target_include_directories(conceptflow_yuv_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
set_target_properties(conceptflow_yuv_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    add_library(conceptflow_yuv_jni SHARED yuv_rgb_jni.cpp)
    target_compile_features(conceptflow_yuv_jni PRIVATE cxx_std_17)
    target_compile_options(conceptflow_yuv_jni PRIVATE
        -O3
        -Wall
        -Wextra
        -Werror
        -fvisibility=hidden
    )
    target_link_options(conceptflow_yuv_jni PRIVATE "LINKER:--no-undefined")
    target_link_libraries(conceptflow_yuv_jni PRIVATE conceptflow_yuv_core log)
endif()

include(CTest)
if(BUILD_TESTING AND NOT ANDROID)
    add_executable(conceptflow_yuv_converter_test yuv_rgb_converter_test.cpp)
    target_compile_features(conceptflow_yuv_converter_test PRIVATE cxx_std_17)
    target_compile_options(conceptflow_yuv_converter_test PRIVATE -Wall -Wextra -Werror)
    target_link_libraries(conceptflow_yuv_converter_test PRIVATE conceptflow_yuv_core)
    add_test(NAME conceptflow_yuv_converter_test COMMAND conceptflow_yuv_converter_test)
endif()
