# ---------------------------------------------------------------------------
# Vendored SPIRV-HeadersConfig.cmake (Hermes Llama / Android NDK)
#
# llama.cpp calls find_package(SPIRV-Headers CONFIG REQUIRED) and
# ggml-vulkan-types.h does #include <spirv/unified1/spirv.hpp>.  The NDK has
# neither, so point the config at the in-tree dev-kit (this file lives in
# <dir>/cmake, so the include root is its parent).
# ---------------------------------------------------------------------------

get_filename_component(_hermes_spirv_root "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)

set(SPIRV-Headers_INCLUDE_DIR  "${_hermes_spirv_root}")
set(SPIRV-Headers_INCLUDE_DIRS "${_hermes_spirv_root}")
set(SPIRV-Headers_FOUND TRUE)

if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${_hermes_spirv_root}")
endif()
