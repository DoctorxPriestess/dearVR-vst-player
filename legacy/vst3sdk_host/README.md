# legacy/vst3sdk_host

This is the original **VST3-SDK** host (`vstrender`), kept for reference only.

It is **not** part of any build that ships:

* the Android app renders through `juce_renderer` (JUCE host) inside wine/box64;
* the PC renderer ships `pc_renderer/vstrender_juce.exe`, built from `juce_renderer/`;
* no script in `tools/` references this tree.

It was moved out of the repository root because it pulled a 37 MB VST3 SDK copy and a
`validator` target (`GLOB_RECURSE` over the SDK test suite) into the default CMake
configuration, which slowed every configure step while producing a binary nobody runs.

The bundled SDK copy lives in `third_party/vst3sdk/` here and is git-ignored like every other
vendored payload.
