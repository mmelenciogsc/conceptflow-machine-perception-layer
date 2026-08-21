# SPDX-License-Identifier: MIT OR Apache-2.0

cmake_minimum_required(VERSION 3.24)

project(
  conceptflow_cuda_cluster_native
  VERSION 0.1.0
  DESCRIPTION "Native scheduling primitives for the CONCEPTFlow CUDA cluster"
  LANGUAGES CXX
)

option(CONCEPTFLOW_ENABLE_CUDA "Enable compilation of future CUDA-backed adapters" OFF)

if(CONCEPTFLOW_ENABLE_CUDA)
  include(CheckLanguage)
  check_language(CUDA)
  if(NOT CMAKE_CUDA_COMPILER)
    message(FATAL_ERROR
      "CONCEPTFLOW_ENABLE_CUDA=ON was requested, but CMake could not find a CUDA compiler. "
      "Install a compatible CUDA toolkit or configure with CONCEPTFLOW_ENABLE_CUDA=OFF."
    )
  endif()
  find_package(CUDAToolkit QUIET)
  if(NOT CUDAToolkit_FOUND)
    message(FATAL_ERROR
      "CONCEPTFLOW_ENABLE_CUDA=ON was requested and a CUDA compiler was found, but the "
      "CUDA toolkit could not be resolved by CMake. Check CUDAToolkit_ROOT or disable CUDA."
    )
  endif()
  enable_language(CUDA)
endif()

add_library(conceptflow_native_scheduler STATIC
  src/scheduler.cpp
)
add_library(conceptflow::native_scheduler ALIAS conceptflow_native_scheduler)

target_compile_features(conceptflow_native_scheduler PUBLIC cxx_std_20)
target_include_directories(conceptflow_native_scheduler
  PUBLIC
    "$<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>"
)

if(CONCEPTFLOW_ENABLE_CUDA)
  target_compile_definitions(conceptflow_native_scheduler PUBLIC CONCEPTFLOW_CUDA_BUILD_ENABLED=1)
endif()

function(conceptflow_enable_strict_warnings target)
  if(MSVC)
    target_compile_options(${target} PRIVATE /W4 /WX /permissive-)
  else()
    target_compile_options(${target} PRIVATE
      -Wall
      -Wextra
      -Wpedantic
      -Wconversion
      -Wsign-conversion
      -Wshadow
      -Werror
    )
  endif()
endfunction()

conceptflow_enable_strict_warnings(conceptflow_native_scheduler)

add_executable(conceptflow_native_demo src/demo.cpp)
target_link_libraries(conceptflow_native_demo PRIVATE conceptflow::native_scheduler)
conceptflow_enable_strict_warnings(conceptflow_native_demo)

include(CTest)
if(BUILD_TESTING)
  find_package(Threads REQUIRED)
  add_executable(conceptflow_native_tests tests/scheduler_tests.cpp)
  target_link_libraries(conceptflow_native_tests
    PRIVATE
      conceptflow::native_scheduler
      Threads::Threads
  )
  conceptflow_enable_strict_warnings(conceptflow_native_tests)
  add_test(NAME conceptflow_native_tests COMMAND conceptflow_native_tests)
endif()
