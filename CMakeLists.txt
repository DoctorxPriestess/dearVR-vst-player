cmake_minimum_required(VERSION 3.20)
project(vstrender LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SRCS
  "third_party/vst3sdk/pluginterfaces/base/coreiids.cpp"
  "third_party/vst3sdk/pluginterfaces/base/funknown.cpp"
  "third_party/vst3sdk/pluginterfaces/base/ustring.cpp"
  "third_party/vst3sdk/pluginterfaces/base/conststringtable.cpp"
  "third_party/vst3sdk/base/source/baseiids.cpp"
  "third_party/vst3sdk/base/source/fbuffer.cpp"
  "third_party/vst3sdk/base/source/fdebug.cpp"
  "third_party/vst3sdk/base/source/fdynlib.cpp"
  "third_party/vst3sdk/base/source/fobject.cpp"
  "third_party/vst3sdk/base/source/fstreamer.cpp"
  "third_party/vst3sdk/base/source/fstring.cpp"
  "third_party/vst3sdk/base/source/timer.cpp"
  "third_party/vst3sdk/base/source/updatehandler.cpp"
  "third_party/vst3sdk/base/thread/source/fcondition.cpp"
  "third_party/vst3sdk/base/thread/source/flock.cpp"
  "third_party/vst3sdk/public.sdk/source/common/commoniids.cpp"
  "third_party/vst3sdk/public.sdk/source/common/commonstringconvert.cpp"
  "third_party/vst3sdk/public.sdk/source/common/memorystream.cpp"
  "third_party/vst3sdk/public.sdk/source/common/readfile.cpp"
  "third_party/vst3sdk/public.sdk/source/common/threadchecker_win32.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/vstinitiids.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/vstpresetfile.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/utility/stringconvert.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/hosting/module.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/hosting/module_win32.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/hosting/hostclasses.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/hosting/plugprovider.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/hosting/parameterchanges.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/hosting/processdata.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/hosting/connectionproxy.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/hosting/eventlist.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/hosting/pluginterfacesupport.cpp"
)

add_library(sdkhost STATIC ${SRCS})
target_include_directories(sdkhost PUBLIC
  "third_party/vst3sdk"
  "third_party/vst3sdk/pluginterfaces"
  "third_party/vst3sdk/base"
  "third_party/vst3sdk/public.sdk"
)
target_compile_definitions(sdkhost PUBLIC _CRT_SECURE_NO_WARNINGS UNICODE _UNICODE)
target_link_libraries(sdkhost PUBLIC ole32 shell32 user32 advapi32)

add_executable(vstrender src/main.cpp)
target_link_libraries(vstrender PRIVATE sdkhost)

file(GLOB_RECURSE TS_SOURCES "third_party/vst3sdk/public.sdk/source/vst/testsuite/*.cpp")
file(GLOB_RECURSE VAL_SOURCES "third_party/vst3sdk/public.sdk/samples/vst-hosting/validator/source/*.cpp")
add_executable(validator ${TS_SOURCES} ${VAL_SOURCES}
  "third_party/vst3sdk/public.sdk/source/main/moduleinit.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/moduleinfo/moduleinfoparser.cpp"
  "third_party/vst3sdk/public.sdk/source/vst/utility/testing.cpp"
)
target_link_libraries(validator PRIVATE sdkhost)
