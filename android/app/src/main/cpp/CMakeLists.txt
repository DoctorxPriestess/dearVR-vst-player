# Native decoder for ALAC / FLAC (built into libvstdecode.so) plus the FLAC encoder
# used when a lossless render is exported.
cmake_minimum_required(VERSION 3.18.1)
project(vstdecode C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Vendored libFLAC (BSD-3-Clause, see THIRD-PARTY-NOTICES.md). Only the encoder side is
# compiled: decoding goes through the native ALAC/MP4 path and dr_flac.
set(FLAC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/flac)

set(FLAC_ENCODER_SOURCES
    ${FLAC_DIR}/libFLAC/bitmath.c
    ${FLAC_DIR}/libFLAC/bitreader.c
    ${FLAC_DIR}/libFLAC/bitwriter.c
    ${FLAC_DIR}/libFLAC/cpu.c
    ${FLAC_DIR}/libFLAC/crc.c
    ${FLAC_DIR}/libFLAC/fixed.c
    ${FLAC_DIR}/libFLAC/float.c
    ${FLAC_DIR}/libFLAC/format.c
    ${FLAC_DIR}/libFLAC/lpc_flac.c
    ${FLAC_DIR}/libFLAC/md5.c
    ${FLAC_DIR}/libFLAC/memory.c
    ${FLAC_DIR}/libFLAC/stream_decoder.c
    ${FLAC_DIR}/libFLAC/stream_encoder.c
    ${FLAC_DIR}/libFLAC/stream_encoder_framing.c
    ${FLAC_DIR}/libFLAC/window_flac.c
)

add_library(vstdecode SHARED
    nd_decode.c
    nd_alac.c
    nd_mp4.c
    nd_flac_impl.c
    nd_flac_end.c
    jni_bridge.c
    ${FLAC_ENCODER_SOURCES}
)

# FLAC_DIR is deliberately NOT an include directory: it contains an assert.h that would
# shadow the system one for every file in this target (dr_flac.h calls assert()).
target_include_directories(vstdecode PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

# audit #22: keep -Wextra on; the unused static inline (nd_rd_u24be) is gone,
# so -Wno-unused-function is no longer needed.
target_compile_options(vstdecode PRIVATE -O2 -Wall -Wextra)
# libFLAC normally takes these from configure; format.c uses the version for its
# vendor string, and the encoder's verify mode links against the decoder.
target_compile_definitions(vstdecode PRIVATE PACKAGE_VERSION="1.4.3" FLAC__HAS_OGG=0)
# libFLAC predates the stricter warnings and is third party code: keep its noise out.
# NDEBUG is required, not cosmetic: flac/assert.h only pulls in <assert.h> when it is not
# defined, so a debug build without it fails on an implicit declaration of assert().
set_source_files_properties(${FLAC_ENCODER_SOURCES}
    PROPERTIES COMPILE_OPTIONS "-w" COMPILE_DEFINITIONS "NDEBUG;HAVE_LROUND=1")

# Android logging (optional; absent on the PC test build).
find_library(nd_android_log log)
if(nd_android_log)
    target_link_libraries(vstdecode ${nd_android_log})
endif()
