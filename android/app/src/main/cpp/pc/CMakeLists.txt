# PC side build of the decoder and encoder sources (MSVC / any C11 toolchain).
cmake_minimum_required(VERSION 3.18.1)
project(ndpctest C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(FLAC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/flac)

add_executable(ndpctest
    nd_pc_main.c
    ../nd_decode.c
    ../nd_alac.c
    ../nd_mp4.c
    ../nd_flac_impl.c
)

target_include_directories(ndpctest PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../third_party
)

if(MSVC)
    target_compile_options(ndpctest PRIVATE /W3 /O2)
    target_compile_definitions(ndpctest PRIVATE _CRT_SECURE_NO_WARNINGS)
else()
    target_compile_options(ndpctest PRIVATE -O2 -Wall)
endif()

if(WIN32)
    target_link_libraries(ndpctest PRIVATE psapi)
endif()

# WAV -> FLAC encoder, with the vendored libFLAC sources.
add_executable(flacenctest
    flac_enc_main.c
    ../nd_flac_end.c
    ../third_party/flac/win_utf8_io/win_utf8_io.c
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
# The vendored tree is not an include directory: its assert.h shadows the system one.
target_include_directories(flacenctest PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/..
)
# libFLAC's export.h turns FLAC_API into dllimport on Windows unless this is set; format.c
# takes its vendor string from PACKAGE_VERSION, which configure would normally define.
target_compile_definitions(flacenctest PRIVATE FLAC__NO_DLL PACKAGE_VERSION="1.4.3" FLAC__HAS_OGG=0 HAVE_LROUND=1)
if(MSVC)
    target_compile_options(flacenctest PRIVATE /W3 /O2)
    target_compile_definitions(flacenctest PRIVATE _CRT_SECURE_NO_WARNINGS)
else()
    target_compile_options(flacenctest PRIVATE -O2 -Wall)
endif()

option(ND_TRACE "Emit decoder trace lines to stderr" OFF)
if(ND_TRACE)
    target_compile_definitions(ndpctest PRIVATE ND_DEBUG_LOG)
endif()
option(ND_BUILD_JNI "Build a desktop DLL of the JNI bridge for a Java smoke test" OFF)
if(ND_BUILD_JNI)
    add_library(vstdecode_jni SHARED
        ../jni_bridge.c
        ../nd_decode.c
        ../nd_alac.c
        ../nd_mp4.c
        ../nd_flac_impl.c
        ../nd_flac_end.c
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
    target_include_directories(vstdecode_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/..
        ${CMAKE_CURRENT_SOURCE_DIR}/../third_party
        $ENV{JAVA_HOME}/include
        $ENV{JAVA_HOME}/include/win32
    )
    set_target_properties(vstdecode_jni PROPERTIES OUTPUT_NAME vstdecode)
    target_compile_definitions(vstdecode_jni PRIVATE FLAC__NO_DLL PACKAGE_VERSION="1.4.3" FLAC__HAS_OGG=0)
    if(MSVC)
        target_compile_options(vstdecode_jni PRIVATE /O2)
        target_compile_definitions(vstdecode_jni PRIVATE _CRT_SECURE_NO_WARNINGS)
    endif()
endif()
