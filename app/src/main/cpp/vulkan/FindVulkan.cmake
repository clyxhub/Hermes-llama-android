# ---------------------------------------------------------------------------
# Vendored FindVulkan.cmake  (Hermes Llama / Android NDK cross-compilation)
#
# Why this exists:
#   * The Android NDK sysroot ships a libvulkan *loader stub* but NO Vulkan
#     headers.
#   * CMake's bundled FindVulkan would happily locate the *host* x86_64
#     Vulkan (wrong ABI for an arm64 target).
#
# So we resolve deterministically:
#   * headers -> the in-tree dev-kit beside this file   (<dir>/vulkan)
#   * loader  -> the NDK sysroot libvulkan.so            (find_library)
#   * glslc   -> a host tool                             (find_program, NEVER)
# ---------------------------------------------------------------------------

set(_hermes_vulkan_root "${CMAKE_CURRENT_LIST_DIR}")

# --- headers (vendored; must not be re-rooted into the sysroot) ------------
# The vendored headers live at <root>/vulkan/*.h(pp) and the SPIR-V headers at
# <root>/spirv/unified1/*.  The *include root* is therefore <root> itself, so
# that `#include <vulkan/vulkan.h>`, `#include <vulkan/vulkan.hpp>` and
# `#include <spirv/unified1/spirv.hpp>` all resolve.  (llama.cpp never links
# SPIRV-Headers::SPIRV-Headers, so piggy-backing the SPIR-V headers on this
# include root is what makes ggml-vulkan-types.h compile.)
if(NOT Vulkan_INCLUDE_DIR)
    set(Vulkan_INCLUDE_DIR "${_hermes_vulkan_root}")
endif()
set(Vulkan_INCLUDE_DIRS "${Vulkan_INCLUDE_DIR}")

# --- loader library (NDK sysroot stub) -------------------------------------
if(NOT Vulkan_LIBRARY)
    find_library(Vulkan_LIBRARY NAMES vulkan libvulkan.so)
endif()
set(Vulkan_LIBRARIES "${Vulkan_LIBRARY}")

# --- glslc shader compiler (host tool) -------------------------------------
if(NOT Vulkan_GLSLC_EXECUTABLE)
    find_program(Vulkan_GLSLC_EXECUTABLE NAMES glslc glslc.exe
                 NO_CMAKE_FIND_ROOT_PATH)
endif()

include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(Vulkan
    REQUIRED_VARS Vulkan_LIBRARY Vulkan_INCLUDE_DIR Vulkan_GLSLC_EXECUTABLE)

if(Vulkan_FOUND AND NOT TARGET Vulkan::Vulkan)
    add_library(Vulkan::Vulkan UNKNOWN IMPORTED)
    set_target_properties(Vulkan::Vulkan PROPERTIES
        IMPORTED_LOCATION "${Vulkan_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${Vulkan_INCLUDE_DIR}")
endif()
