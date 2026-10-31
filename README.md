# VST Player

An Android player that renders audio **through VST3 plugins**: a wine + box64 + proot guest runs a
JUCE VST3 host, and the app drives that host over a line protocol.

Only the **`standalone`** flavor is released: that APK carries the environment and the plugin
payload, so it works out of the box ([Releases](../../releases)). The second flavor, `termux`, ships
no environment and is not distributed — build it yourself if you need it (see
[Building the `termux` flavor](#building-the-termux-flavor)).

| Flavor | applicationId | Environment | Published |
| --- | --- | --- | --- |
| `standalone` | `com.vstp` | Self-contained: the APK carries a rootfs, wine, box64 and the plugin payload as assets and unpacks them on first run. | yes, under [Releases](../../releases) |
| `termux` | `com.vstp.tx` | Ships no environment at all: same code, its own applicationId, so it can be installed next to a standalone copy. | no — build it yourself |

## Layout

```
android/                  the app
  app/src/main/java/com/vstplayer/
    MainActivity.java     three tabs (home / library / VST) + detail overlay
    core/                 player, engine, decode, tags, ReplayGain, SAF library
    ui/                   hand-written views (no androidx, no reachability)
  app/src/main/cpp/       native ALAC/FLAC/MP4 decoder (libvstdecode.so)
  app/src/standalone/     environment assets for the standalone flavor (built locally, not committed)
juce_renderer/            the VST3 host that runs inside wine (JUCE)
build_payload/            staging area for the plugin payload archive
third_party/              vendored JUCE (git-ignored)
tools/                    build, benchmark and asset tooling (see tools/README.md)
legacy/                   the old VST3-SDK host, kept for reference only
docs/                     architecture, benchmarks, historical reports
```

## What is not in this repository

Sources only. Everything below is deliberately absent, so a build from a clean clone produces an
APK that starts but has no runtime and no plugin until you supply them:

| Missing piece | Why | How to get it |
| --- | --- | --- |
| `android/app/src/standalone/assets/` (~298 MB) | rootfs, wine, box64 and the plugin payload; far too large to version, and the payload contains third-party binaries | untar an environment of your own and split it with `tools/assets/*`, or start from the released APK |
| `third_party/JUCE` | ~130 MB vendored framework | clone JUCE and point `juce_renderer/CMakeLists.txt` at it |
| `dearVR PRO 2.vst3` | the plugin itself | see [Credits](#credits-and-third-party-components) |

## Requirements

* JDK 17 (`JAVA_HOME`)
* Android SDK with platform 35, build-tools and **NDK 28.2.13676358** (pinned in `app/build.gradle.kts`)
* CMake 3.22+ and Ninja for the native build (AGP picks them up from the SDK)
* A device with `arm64-v8a`; the standalone flavor needs ~1 GB free for the unpacked environment

The Gradle wrapper is checked in. `tools/build.sh` prefers a locally unpacked distribution at
`tools/gradle-8.14.3` and falls back to `android/gradlew`.

If your network needs a proxy for the dependency repositories, uncomment the `systemProp.*.proxy*`
block in `android/gradle.properties` (or move it to `~/.gradle/gradle.properties`).

## Build and install

```bash
tools/build.sh assemble            # debug APK (~280 MB) for the standalone flavor
tools/build.sh install             # build + push + pm install to $DEV
tools/build.sh check               # compile gate only (Java + native)
DEV=192.0.2.10:5555 tools/build.sh install   # DEV is your adb serial
```

Wireless adb is the supported path. Installing a ~280 MB APK works reliably through
`adb push` + `pm install --user 0`; plain `adb install` can stall. On vivo/iQOO ROMs a
"external source" confirmation dialog still has to be accepted on the device.

### Building the `termux` flavor

Both flavors come from the same sources; only the bundled assets differ (`src/standalone/assets` is
flavor specific), so the termux APK is small and needs no payload:

```bash
tools/build.sh assemble termux        # or: cd android && ./gradlew assembleTermuxDebug
# -> android/app/build/outputs/apk/termux/debug/app-termux-debug.apk  (~2.4 MB)
```

It has its own applicationId (`com.vstp.tx`), so it installs alongside the standalone build. Because
it carries no environment it will not unpack one: you have to supply the guest yourself under
`/data/data/com.vstp.tx/files/` — `bin/proot`, `glibc/bin/bash`, `glibc/bin/box64` and the
`.ready-<VERSION>` marker that `Env.isReady()` checks (`VERSION` lives in `core/Env.java`). A
debug build plus `adb shell run-as com.vstp.tx` is the practical way to copy a tree in; the unpacked
`files/` directory of a standalone install is the easiest source for it.

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

## Credits and third-party components

**dearVR PRO 2** is a spatial audio plugin by [Dear Reality](https://www.dear-reality.com/), part of
the Sennheiser Group since 2019. Dear Reality stopped developing its plugins in 2025: its license
manager was deactivated on 2025-07-31, and Sennheiser now makes the plugins available free of charge,
installable and usable *"without license activation or restrictions"*
([announcement](https://help.sennheiser.com/hc/en-us/articles/28365062393874-Transition-of-Dear-Reality-Important-Information-for-Existing-Users),
[coverage](https://rekkerd.org/dear-reality-immersive-audio-plugins-now-free-at-sennheiser/)).
All rights remain with the plugin's rights holders. This project is an unofficial, non-commercial
host for that plugin; it bundles the vendor's free build in the released APK only so the app works
out of the box, and claims no rights to it. **If you are a rights holder and want anything removed,
open an issue and it will be taken down promptly.**

The JUCE-based host, the guest environment (proot, box64, wine) and the native decoders carry their
own licenses; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## License

GPL-3.0. See [LICENSE](LICENSE). The bundled third-party binaries are **not** relicensed by this.
