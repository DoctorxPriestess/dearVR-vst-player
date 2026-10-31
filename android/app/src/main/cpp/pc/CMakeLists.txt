# PC side build of the decoder sources (MSVC / any C11 toolchain).
cmake_minimum_required(VERSION 3.18.1)
project(ndpctest C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

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
    )
    target_include_directories(vstdecode_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/..
        ${CMAKE_CURRENT_SOURCE_DIR}/../third_party
        $ENV{JAVA_HOME}/include
        $ENV{JAVA_HOME}/include/win32
    )
    set_target_properties(vstdecode_jni PROPERTIES OUTPUT_NAME vstdecode)
    if(MSVC)
        target_compile_options(vstdecode_jni PRIVATE /O2)
        target_compile_definitions(vstdecode_jni PRIVATE _CRT_SECURE_NO_WARNINGS)
    endif()
endif()
