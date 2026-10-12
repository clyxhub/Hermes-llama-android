cmake_minimum_required(VERSION 3.22.1)
project(hermes_llama CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# llama.cpp is expected as a git submodule at <repo>/third_party/llama.cpp
# For stage (a) we only enable the CPU backend (no Vulkan / OpenCL / QNN).
# Stage (b) flips GGML_VULKAN=ON; stage (g) adds the QNN flavour.
# ---------------------------------------------------------------------------
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)
get_filename_component(LLAMA_DIR ${LLAMA_DIR} ABSOLUTE)

if(EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    set(LLAMA_BUILD_COMMON ON  CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS  OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN  OFF CACHE BOOL "" FORCE)
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

target_link_libraries(hermes_llama
    llama
    common
    ggml
    android
    log)
