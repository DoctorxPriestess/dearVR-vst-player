cmake_minimum_required(VERSION 3.22)
project(vstrender_juce VERSION 1.0.0 LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../third_party/JUCE JUCE)
juce_add_console_app(vstrender_juce PRODUCT_NAME "vstrender")
target_sources(vstrender_juce PRIVATE src/juce_main.cpp)
# audit #22: the host source gets -Wall -Wextra too (MSVC uses /W4).
if (MSVC)
  target_compile_options(vstrender_juce PRIVATE /W4)
else()
  target_compile_options(vstrender_juce PRIVATE -Wall -Wextra)
endif()
target_compile_definitions(vstrender_juce PRIVATE
  JUCE_WEB_BROWSER=0
  JUCE_USE_CURL=0
  JUCE_PLUGINHOST_VST3=1
  JUCE_MODAL_LOOPS_PERMITTED=1
)
target_link_libraries(vstrender_juce PRIVATE
  juce::juce_audio_processors
  juce::juce_audio_formats
  juce::juce_audio_utils
  juce::juce_gui_basics
  juce::juce_graphics
  juce::juce_events
  juce::juce_core
  juce::juce_data_structures
)