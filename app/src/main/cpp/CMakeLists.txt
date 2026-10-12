cmake_minimum_required(VERSION 3.22.1)
project(hermes_llama CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# llama.cpp is expected as a git submodule at <repo>/third_party/llama.cpp
# Stage (a) shipped the CPU backend only. Stage (b) (this change) enables the
# Vulkan GPU backend for Adreno/arm64 devices.
#
# The Android NDK ships a stub libvulkan.so *loader* for linking, but no
# Vulkan headers and no SPIR-V headers. Both are vendored in-tree under
# ./vulkan so the build is hermetic (nothing installed system-wide):
#
#   vulkan/                     -> Vulkan C headers (vulkan.h, vulkan_core.h, ...)
#   vulkan/spirv/unified1/      -> SPIR-V headers (spirv.h, spirv.hpp, ...)
#   vulkan/FindVulkan.cmake     -> our `find_package(Vulkan COMPONENTS glslc)`
#   vulkan/cmake/SPIRV-HeadersConfig.cmake
#                               -> our `find_package(SPIRV-Headers CONFIG)`
# ---------------------------------------------------------------------------
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)
get_filename_component(LLAMA_DIR ${LLAMA_DIR} ABSOLUTE)

set(HERMES_VULKAN_DEVKIT ${CMAKE_CURRENT_SOURCE_DIR}/vulkan)

# `find_package(Vulkan ...)` -> pick up our vendored FindVulkan.cmake.
list(APPEND CMAKE_MODULE_PATH "${HERMES_VULKAN_DEVKIT}")
# `find_package(SPIRV-Headers CONFIG ...)` -> point straight at the vendored
# config package (and let CONFIG-mode discovery scan the dev-kit prefix).
set(SPIRV-Headers_DIR "${HERMES_VULKAN_DEVKIT}/cmake" CACHE PATH "" FORCE)
list(APPEND CMAKE_PREFIX_PATH "${HERMES_VULKAN_DEVKIT}")

if(EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    set(LLAMA_BUILD_COMMON ON  CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS  OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN  ON  CACHE BOOL "" FORCE)   # <-- stage (b)
    set(GGML_OPENCL  OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS ON  CACHE BOOL "" FORCE)
    add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama.cpp)
else()
    message(FATAL_ERROR
        "llama.cpp not found at ${LLAMA_DIR}. "
        "Run: git submodule update --init --recursive")
endif()

add_library(hermes_llama SHARED llama_jni.cpp)

target_include_directories(hermes_llama PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
    ${LLAMA_DIR}/ggml/include)

# In this llama.cpp revision the common library target is `llama-common`
# (outputs libllama-common.a); passing `common` makes ld.lld fail with
# "unable to find library -lcommon".
#
# NOTE: the Vulkan backend (libggml-vulkan.so) is deliberately NOT listed here.
# It is built by the ggml CMake super-build and packaged by AGP like every
# other shared lib, then discovered at runtime by llama_backend_init() ->
# ggml_backend_load_all(). Linking it would create a hard DT_NEEDED on
# libvulkan.so for devices that may not ship a Vulkan loader.
target_link_libraries(hermes_llama
    llama
    llama-common
    ggml
    android
    log)
