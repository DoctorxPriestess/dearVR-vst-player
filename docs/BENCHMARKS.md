# Benchmarks

Every number here is measured on the same device with the same commands, so a later run can be
compared directly. The reference device is an **iQOO V2546A** (Android 16, 8 cores: 2x4.6 GHz +
3x3.6 GHz + 3x2.0 GHz, display 1440x3168).

## Cold start

```bash
tools/bench/bootbench.sh 3
```

The script force-stops the app, launches it and watches `files/diag.log` for the `params loaded`
line; the reported figure is the diag-timestamp delta from `shell onCreate`, i.e. process start to
"engine ready and the parameter table is loaded". The wall-clock figure next to it includes the
`am start` round trip.

| Build | Run 1 | Run 2 | Run 3 | Median |
| --- | --- | --- | --- | --- |
| baseline (2026-10-31, pristine copy) | 4012 ms | 3684 ms | 3596 ms | **3684 ms** |

## First-run asset extraction

```bash
tools/bench/extractbench.sh
```

Deletes the `.ready-*` markers so the next launch unpacks the ~300 MB environment again, then
reports the wall time until `env extraction done` and the resulting file count under `files/glibc`.
Baseline (from `docs/history/OPTIMIZATION-2026-10-30.md`): 15.6 s for the unpack itself, ~75 s
until the first usable state (wine prefix creation dominates).

## Render throughput

```bash
# debug-only autorun hook: render every file in work/in when the VST tab opens
printf 'render.autorun=1\r\nrender.segment=300\r\nrender.preroll=10\r\nrender.engines=2\r\n' > boot.conf
adb push boot.conf /data/local/tmp/ && adb shell run-as com.vstp cp /data/local/tmp/boot.conf files/boot.conf
```

Then open the VST tab; `diag.log` reports per-file times and a `batch done` summary.

Baseline batch (5 files / 7 slices / 2 lanes, sources = 620 s WAV, 24-bit FLAC, two 30 s m4a, 1 s WAV):

```
batch done: files=5 ok=5 failed=0 slices=7 lanes=2 total +66714ms
```

The rendered WAVs are exported to the SAF folder chosen in the app. Because the whole pipeline is
deterministic, the **sha256 of every output is a regression test**: re-running the same batch must
reproduce these hashes byte for byte.

| Output | Bytes | sha256 |
| --- | --- | --- |
| `01. 60W_vst.wav` | 8 640 104 | `72004354d84ced2501c52446b9b818a8d004d6aa88cdccfe11ce0556058c816a` |
| `02. deep_vst.wav` | 8 640 104 | `b2a0cf30d43c0bea249f9b743a9506459cbd17f2000eee8721816e12738dc5b1` |
| `long620_vst.wav` | 119 040 044 | `06990d8a685686d86819e65c7f96aeed7cbe38158bd271cea6c4e177343e4fb3` |
| `src_flac24_vst.wav` | 5 760 104 | `82ac58ee8eba4318b31adb7e116314b412926b5fda9d790ee6462add3fc07f45` |
| `tiny_vst.wav` | 288 104 | `989eb2564b3cd8d27ec5c199556a44f582c8a6f5ff6d33b583aecf071dbc6d7a` |

## UI frame time

```bash
adb shell dumpsys gfxinfo com.vstp reset
# ... perform the interaction under test ...
adb shell dumpsys gfxinfo com.vstp | head -20
```

`dumpsys gfxinfo` only counts frames the window actually drew, so scripted taps that trigger a
handful of frames produce noisy percentages. Compare the **90th/95th percentile and the janky
frame count** for the same scripted interaction rather than the percentage alone.

## APK size

```bash
ls -l android/app/build/outputs/apk/standalone/debug/app-standalone-debug.apk
```

Baseline: **278 920 586 bytes** (266 MiB, shown as 279 MB in the installer). ~298 MB of that is
the unpacked environment assets; the assets are already xz-compressed and are stored uncompressed
in the APK (`noCompress "bin"`), so gzip would only waste install time.
