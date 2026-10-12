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

# ---------------------------------------------------------------------------
# Hermes downstream patch for the vendored llama.cpp submodule.
#
# ggml/src/ggml-vulkan/ggml-vulkan.cpp only ever calls
#   VULKAN_HPP_DEFAULT_DISPATCHER.init(vk_instance.instance);
# On Android the loader does not resolve every *device-level* entry point via
# vkGetInstanceProcAddr() (notably core Vulkan 1.2 device commands such as
# vkGetBufferDeviceAddress), so those DispatchLoaderDynamic slots stay NULL.
# The first real call (buffer creation during model load) then jumps to 0x0 ->
# SIGSEGV. We insert an init(device) immediately after the VkDevice is created.
#
# The submodule points at upstream ggml-org/llama.cpp, so the change is applied
# here (idempotently) instead of being committed into the submodule.
# ---------------------------------------------------------------------------
set(HERMES_VK_SRC "${LLAMA_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp")
if(EXISTS "${HERMES_VK_SRC}")
    file(READ "${HERMES_VK_SRC}" HERMES_VK_TXT)
    string(FIND "${HERMES_VK_TXT}" "HERMES-PATCH(vulkan-device-dispatcher)" HERMES_VK_HAS_PATCH)
    if(HERMES_VK_HAS_PATCH EQUAL -1)
        set(HERMES_VK_ANCHOR "device->device = device->physical_device.createDevice(device_create_info);")
        set(HERMES_VK_REPLACEMENT "${HERMES_VK_ANCHOR}\n\n        // HERMES-PATCH(vulkan-device-dispatcher): the Vulkan-Hpp default dispatcher is\n        // initialised for the instance only. On Android the loader does not resolve\n        // every device-level entry point through vkGetInstanceProcAddr() (e.g. core\n        // Vulkan 1.2 device commands such as vkGetBufferDeviceAddress), leaving those\n        // DispatchLoaderDynamic slots NULL and the first call jumps to 0x0.\n        // init(device) fills them in via vkGetDeviceProcAddr().\n        VULKAN_HPP_DEFAULT_DISPATCHER.init(device->device);\n\n        GGML_LOG_INFO(\"HermesLlama-vk: device dispatcher initialised (createBuffer=%d getBufferDeviceAddress=%d waitSemaphores=%d)\\n\",\n                      (int) (VULKAN_HPP_DEFAULT_DISPATCHER.vkCreateBuffer != nullptr),\n                      (int) (VULKAN_HPP_DEFAULT_DISPATCHER.vkGetBufferDeviceAddress != nullptr),\n                      (int) (VULKAN_HPP_DEFAULT_DISPATCHER.vkWaitSemaphores != nullptr));\n        if (device->buffer_device_address && VULKAN_HPP_DEFAULT_DISPATCHER.vkGetBufferDeviceAddress == nullptr) {\n            GGML_LOG_WARN(\"HermesLlama-vk: vkGetBufferDeviceAddress still unresolved - disabling buffer_device_address\\n\");\n            device->buffer_device_address = false;\n        }")
        string(REPLACE "${HERMES_VK_ANCHOR}" "${HERMES_VK_REPLACEMENT}" HERMES_VK_NEW "${HERMES_VK_TXT}")
        if(NOT HERMES_VK_NEW STREQUAL HERMES_VK_TXT)
            file(WRITE "${HERMES_VK_SRC}" "${HERMES_VK_NEW}")
            message(STATUS "hermes: applied ggml-vulkan device-dispatcher patch")
        else()
            message(WARNING "hermes: ggml-vulkan device-dispatcher anchor not found; patch NOT applied")
        endif()
    else()
        message(STATUS "hermes: ggml-vulkan device-dispatcher patch already present")
    endif()
endif()

# ---------------------------------------------------------------------------
# HERMES-PATCH(vulkan-device-memory)
#
# ggml_backend_vk_get_device_memory() calls vkdev.getMemoryProperties2() -
# a Vulkan 1.1 physical-device command resolved through the default
# dispatcher. On some Android loaders/drivers that slot is left NULL even
# after init(device), so the call jumps to 0x0 -> SIGSEGV (pc 0x0) inside
# ggml_backend_vk_get_device_memory during model load with GPU offload on.
# Guard it and fall back to the Vulkan 1.0 vkGetPhysicalDeviceMemoryProperties.
# ---------------------------------------------------------------------------
if(EXISTS "${HERMES_VK_SRC}")
    file(READ "${HERMES_VK_SRC}" HERMES_VK_TXT2)
    string(FIND "${HERMES_VK_TXT2}" "HERMES-PATCH(vulkan-device-memory)" HERMES_VK2_HAS_PATCH)
    if(HERMES_VK2_HAS_PATCH EQUAL -1)
        set(HERMES_VK2_ANCHOR "vkdev.getMemoryProperties2(&memprops);")
        set(HERMES_VK2_REPLACEMENT "        // HERMES-PATCH(vulkan-device-memory): getMemoryProperties2 is a Vulkan 1.1\n        // physical-device command; on some Android loaders its dispatcher slot stays\n        // NULL and the call jumps to 0x0. Fall back to the Vulkan 1.0 entry point.\n        if (VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceMemoryProperties2 != nullptr) {\n            vkdev.getMemoryProperties2(&memprops);\n        } else {\n            GGML_LOG_WARN(\"HermesLlama-vk: vkGetPhysicalDeviceMemoryProperties2 unresolved - using 1.0 fallback (memory budget disabled)\\n\");\n            memprops.pNext = nullptr;\n            memprops.memoryProperties = vkdev.getMemoryProperties();\n        }")
        string(REPLACE "${HERMES_VK2_ANCHOR}" "${HERMES_VK2_REPLACEMENT}" HERMES_VK2_NEW "${HERMES_VK_TXT2}")
        if(NOT HERMES_VK2_NEW STREQUAL HERMES_VK_TXT2)
            file(WRITE "${HERMES_VK_SRC}" "${HERMES_VK2_NEW}")
            message(STATUS "hermes: applied ggml-vulkan device-memory patch")
        else()
            message(WARNING "hermes: ggml-vulkan device-memory anchor not found; patch NOT applied")
        endif()
    else()
        message(STATUS "hermes: ggml-vulkan device-memory patch already present")
    endif()
endif()

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
