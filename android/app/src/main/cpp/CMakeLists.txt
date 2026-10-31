# Native decoder for ALAC / FLAC (built into libvstdecode.so).
cmake_minimum_required(VERSION 3.18.1)
project(vstdecode C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(vstdecode SHARED
    nd_decode.c
    nd_alac.c
    nd_mp4.c
    nd_flac_impl.c
    jni_bridge.c
)

target_include_directories(vstdecode PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

# audit #22: keep -Wextra on; the unused static inline (nd_rd_u24be) is gone,
# so -Wno-unused-function is no longer needed.
target_compile_options(vstdecode PRIVATE -O2 -Wall -Wextra)

# Android logging (optional; absent on the PC test build).
find_library(nd_android_log log)
if(nd_android_log)
    target_link_libraries(vstdecode ${nd_android_log})
endif()
