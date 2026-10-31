cmake_minimum_required(VERSION 3.22)
project(GainProbe VERSION 1.0.0 LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/JUCE JUCE)
juce_add_plugin(GainProbe
  COMPANY_NAME "probe"
  PLUGIN_MANUFACTURER_CODE Prbe
  PLUGIN_CODE Gnpr
  PRODUCT_NAME "GainProbe"
  IS_SYNTH FALSE
  NEEDS_MIDI_INPUT FALSE
  NEEDS_MIDI_OUTPUT FALSE
  IS_MIDI_EFFECT FALSE
  EDITOR_WANTS_KEYBOARD_FOCUS FALSE
  COPY_PLUGIN_AFTER_BUILD FALSE
  FORMATS VST3)
target_sources(GainProbe PRIVATE src/GainProbe.cpp)
target_compile_definitions(GainProbe PRIVATE JUCE_WEB_BROWSER=0 JUCE_USE_CURL=0 JUCE_VST3_CAN_REPLACE_VST2=0)
target_link_libraries(GainProbe PRIVATE juce::juce_audio_utils PUBLIC juce::juce_recommended_config_flags)
