set -e
cd "D:/AI Misc/vst3_player"
rm -rf build_payload && mkdir -p build_payload
tar -xzf android/app/src/standalone/assets/payload.bin -C build_payload
NEW="juce_renderer/build/vstrender_juce_artefacts/Release/vstrender_juce.exe"
ls -l "$NEW"
cp "$NEW" "build_payload/payload/vstrender_juce.exe"
tar -czf android/app/src/standalone/assets/payload.new.bin -C build_payload payload
ls -l android/app/src/standalone/assets/payload.new.bin