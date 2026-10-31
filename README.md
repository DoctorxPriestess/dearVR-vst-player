# VST Player

An Android player that renders audio **through VST3 plugins**: a wine + box64 + proot guest runs a
JUCE VST3 host, and the app drives that host over a line protocol. Everything you need to build,
install and measure is in this repository.

Two product flavors are produced from the same sources:

| Flavor | applicationId | Environment |
| --- | --- | --- |
| `standalone` | `com.vstp` | Fully self-contained: the APK carries a rootfs, wine and the plugin payload as assets and unpacks them on first run. |
| `termux` | `com.vstp.tx` | Reuses a Termux installation instead of shipping its own environment. |

## Layout

```
android/                  the app
  app/src/main/java/com/vstplayer/
    MainActivity.java     three tabs (home / library / VST) + detail overlay
    core/                 player, engine, decode, tags, ReplayGain, SAF library
    ui/                   hand-written views (no androidx, no reachability)
  app/src/main/cpp/       native ALAC/FLAC/MP4 decoder (libvstdecode.so)
  app/src/standalone/     ~300 MB of environment assets for the standalone flavor
juce_renderer/            the VST3 host that runs inside wine (JUCE)
build_payload/            staging area for the plugin payload archive
pc_renderer/              Windows renderer + preset scripts (same host binary)
third_party/              vendored JUCE and dr_flac (git-ignored)
tools/                    build, benchmark and asset tooling (see tools/README.md)
legacy/                   the old VST3-SDK host, kept for reference only
docs/                     architecture, benchmarks, historical reports
```

## Requirements

* JDK 17 (`JAVA_HOME`)
* Android SDK with platform 35, build-tools and **NDK 28.2.13676358** (pinned in `app/build.gradle.kts`)
* CMake 3.22+ and Ninja for the native build (AGP picks them up from the SDK)
* A device with `arm64-v8a`; the standalone flavor needs ~1 GB free for the unpacked environment

The Gradle wrapper is checked in. `tools/build.sh` prefers a locally unpacked distribution at
`tools/gradle-8.14.3` and falls back to `android/gradlew`.

## Build and install

```bash
tools/build.sh assemble            # debug APK (~280 MB) for the standalone flavor
tools/build.sh install             # build + push + pm install to $DEV
tools/build.sh check               # compile gate only (Java + native)
DEV=192.168.101.154:5555 tools/build.sh install
```

Wireless adb is the supported path. Installing a ~280 MB APK works reliably through
`adb push` + `pm install --user 0`; plain `adb install` can stall. On vivo/iQOO ROMs a
"external source" confirmation dialog still has to be accepted on the device.

## Verify

```bash
tools/gate.sh check                          # serialized compile gate
python tools/check_style.py                  # style rules
tools/bench/bootbench.sh 3                   # cold start -> engine ready (ms)
tools/bench/extractbench.sh                  # first-run asset extraction
tools/build.sh lint                          # Android Lint report
```

`docs/BENCHMARKS.md` records what each number means and the current baselines.

## Runtime shape (short version)

1. `Env` unpacks the environment assets into `files/glibc` (6 parallel segments, idempotent).
2. `Engine` spawns `proot` + `box64` + `wine` + `vstrender_juce.exe -serve` and speaks the line
   protocol (`READY`, `PARAMS|`, `SET|`, `PRESET|`, `SAVE|`, `<in>|<out>`, `PROG`, `OK`, `ERR`).
3. `Vst` keeps one primary engine plus optional helper lanes pinned to distinct cores; long files are
   split into overlapping segments, rendered in parallel and joined back sample-exactly.
4. Non-PCM sources (ALAC/AAC/MP3/OGG, 16-bit FLAC) are decoded to a temporary WAV first; 24-bit FLAC
   is handed to the host untouched so the bit depth survives.
5. Finished renders are copied to a SAF folder by `Export` on a background thread.

See `docs/ARCHITECTURE.md` for the detailed pipeline.

## Constraints worth knowing

* `minSdk 24` / `targetSdk 28`. The low target keeps legacy external storage working; raising it
  changes how the whole library browses files, so it is deliberately not raised here.
* No AndroidX, no third-party UI libraries: every view is hand-written to keep the APK small and the
  build dependency-free.
* Batteries: rendering in the background depends on the OEM power policy. Stock Android honours the
  foreground service + wake lock; some ROMs freeze the process anyway (see `docs/history/`).
