# tools

Everything here is a developer utility; none of it ships inside the APK.

## Entry points

| Command | Purpose |
| --- | --- |
| `tools/build.sh assemble` | Build the debug APK. |
| `tools/build.sh install` | Build, then push + `pm install` to the device. |
| `tools/build.sh check` | Compile gate: Java sources + native library. |
| `tools/build.sh lint` | Android Lint report (`android/app/build/reports/`). |
| `tools/build.sh clean` | Remove build outputs. |
| `tools/gate.sh [check\|full]` | Serialized compile gate for concurrent editors. |

`tools/build.sh assemble` plus `gradle -PencodeSmoke assembleStandaloneDebug` builds an
x86_64 APK for an emulator, so the delivery encoders (`core/Encode`, the FLAC encoder in
`libvstdecode.so`) can be exercised without an arm64 device: push a file named `encode-selftest`
into the app's `files/` directory and the next start encodes everything in `files/selftest` once and
writes `files/encode-selftest.txt` (see `core/EncodeSmoke`).

Scripted batches in a debug build are driven by `files/boot.conf`, one `KEY=VALUE` per line
(`core/BootConf` refuses the file in a release build):

| Key | Effect |
| --- | --- |
| `ui.tab=N` | Opens tab 0 (home), 1 (library) or 2 (render) right after start; the render page is built lazily, so a headless run needs this. |
| `render.autorun=1` | Queues every source in the guest `work/in` directory and starts the batch once the engine reports ready. |
| `decode.selftest=1` / `decode.one=NAME` | Decode the codec fixtures, or one named file, and log the result. |

## Layout

```
tools/
  build.sh          build / install / check / lint / clean
  gate.sh           file-locked compile gate (one gradle build at a time)
  check_style.py    style rules (LF, no tabs, <=120 cols, no wildcard imports)
  build_install.sh  deprecated alias for "build.sh install"
  bench/            on-device measurement
    bootbench.sh      cold start: process start -> engine ready
    extractbench.sh   first-run asset extraction timing
    bench_extract.py  pull rendered WAVs and measure realtime factor
    seam_analysis.py  segment-join quality analysis (no click detection)
  assets/           APK asset pipeline
    split_assets.py   split the large tarballs into N segments
    verify_split.py   verify a split is byte-identical to the original
    scan_tars.py      inspect tar segment contents
    repack_payload.sh rebuild the payload segment from a fresh JUCE host
    convert_foobar_presets.py  foobar2000 -> VST3 preset conversion
    split_manifest.json        the split plan actually used for the assets
    presets/                   converted presets used for render tests
  dev/              one-off probes kept for reproducibility (not maintained)
  rg/               PC-side harness that verifies the ReplayGain core
  vst3probe/        minimal gain VST3 used to isolate host problems
```

`tools/bench60.wav` and `tools/art_test.wav` are local test fixtures (git-ignored).

## Device setup

The benchmarks talk to a device over wireless adb:

```bash
adb connect 192.0.2.10:5555
DEV=192.0.2.10:5555 tools/bench/bootbench.sh 3
```

`ADB` and `DEV` are honoured by every script in `bench/`.
`tools/build.sh install` uses `adb push` + `pm install --user 0` because
`adb install` on a ~280 MB APK over wireless adb is unreliable.
