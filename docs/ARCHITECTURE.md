# Architecture

## 1. Layers

```
MainActivity ── ui/ ─────────── hand-written views, one per tab + detail overlay
      │            HomeView, LibraryView, VstView, DetailPage, PlaylistPage,
      │            TrackList, CoverMorph, CoverLoader, Slider, Icon, Ui
      └── core/ ───────────── all state and IO
                   PlaybackService ─┐
                   Player ──────────┴─ MediaPlayer playback, 250 ms progress tick
                   Vst ─────────────── render queue, lanes, parameter sync
                   Engine ──────────── proot/wine process + line protocol
                   Env ─────────────── asset extraction, boot.conf, wine prefix fixes
                   Decode, NativeDecode, Segment, FastExtract, Seekable
                   LibraryRef, Tags, Mp4Tags, Playlists, Lyrics, Export
                   Loudness, RgTags, RgStore, ReplayGain
                   Diag, Paths, Param, App
```

There is no dependency injection and no AndroidX: `Vst.get()` is a process-wide singleton, the
tab views are fields of `MainActivity`, and every view builds its own child hierarchy in code.

## 2. Threading model

| Thread | Owns |
| --- | --- |
| main | view tree, all callbacks posted by `Ui.post`/`Handler` |
| `vst-export` (single) | SAF writes of finished renders (`Export`) |
| `vst-prep` (single) | decode + WAV slicing for the render queue |
| `CoverLoader` (single) | cover decode, tag reads, backdrop blur ladder |
| engine reader (per engine) | `Engine.pump()`: one thread draining the host's stdout |
| `rg-scan` / `rg-scan-ref` | ReplayGain scans |

Rule of thumb used throughout: main-thread code never touches a `File`, a `ContentResolver`
query or a codec. Anything that can block is pushed to one of the lanes above and the result is
posted back.

## 3. Startup sequence

1. `MainActivity.onCreate` → `Diag.init` (resident `FileOutputStream`, 4 MB rotation) →
   `Env.init` → `Vst.get()`.
2. `Env` checks the `.ready-*` markers under `files/`. On first run it extracts six asset
   segments in parallel (`FastExtract`) into `files/glibc`, creates symlinks afterwards, and fixes
   the wine prefix (`dosdevices/z:`, plugin data under `drive_c/ProgramData`).
3. `Vst.startEngine()` → `Engine.start()` builds the guest command line and spawns
   `proot-loader` (proot + box64 + wine) with the host in `-serve` mode.
4. The host prints `READY`; `Vst` then issues `PARAMS|` to materialise the parameter table and
   posts `params loaded` to `Diag`. That line is the end of the cold-start measurement.
5. If the first spawn dies without output, `Engine` retries once with `PROOT_NO_SECCOMP=1`
   (kernels that reject the seccomp filter). The retry is bounded.

Cold start on the reference device: **~3.7 s** from process creation to `params loaded`
(`docs/BENCHMARKS.md`), of which ~4.5 s wall includes the `am start` round trip.

## 4. Engine protocol

One command per line on the guest's stdin, one response per line on stdout, in order.
`Engine` serialises access (a command queue per engine) and `Vst` correlates responses with
callbacks on the reader thread, posting UI effects back to main.

| Line | Meaning | Reply |
| --- | --- | --- |
| `READY [ch]` | host finished loading the plugin (pushed, not a reply) | – |
| `PROG <pct>` | render progress (pushed) | – |
| `PARAMS|<json>` | dump the parameter table (id, name, text, normalised value) | `OK <path>` |
| `GET|<id>` | read one parameter | `OK <value>|<text>` |
| `SET|<id>|<0..1>` | write one parameter | `OK <text>` |
| `PRESET|<file.vstpreset>` | load a preset | `OK` |
| `SAVE|<file.vstpreset>` | save the current state | `OK` |
| `RESET [hard|soft]` | reset parameters | `OK` |
| `<in.wav>|<out.wav>` | render one file (paths are wine `Z:\...` paths) | `PROG`* then `OK`/`ERR` |
| `QUIT` | shut the host down | – |

Paths are always translated by `Vst.gp()` from a host file under `files/glibc` to the matching
`Z:\` path, because the guest cannot see the app's data directory any other way.

## 5. Render pipeline

```
sources ──plan()──► slices ──queue──► lanes (1..3 engines) ──► join ──► Export (SAF)
            │                                                   │
      decode if needed                                    sample-exact trim
```

* **plan()** (`VstView`): for each source decide whether the host can read it directly
  (WAV/AIFF, 24-bit FLAC) or whether it must be decoded to a temporary WAV first
  (16-bit FLAC, ALAC, AAC, MP3, OGG). The decision keeps the output bit depth equal to the source.
* **slicing** (`Segment`): only files longer than 10 minutes are cut, into 300 s segments with a
  10 s pre-roll; the pre-roll is rendered again in the next segment and dropped at join time, which
  makes each segment bit-identical to the same region of a full-file render. Files shorter than
  10 minutes are never split because slicing costs 5-8 % without giving parallelism.
* **lanes** (`Vst`): one primary engine plus up to two helpers, each pinned to a different core
  (`taskset`, mask derived from `cpuinfo_max_freq`). The number of lanes is decided from
  `ActivityManager.MemoryInfo`: 3 only when the device has >= 6 GB total and >= 2.6 GB available.
  Helpers get the primary's 36 parameters replayed after `READY` (`syncParamsTo`), one lane at a
  time, so both sides of a join are rendered with identical plugin state.
* **join** (`Segment.join`): concatenates the segments into one RIFF file, dropping each segment's
  pre-roll; headers are rewritten with the summed size.
* **export** (`Export`): copies the finished WAV into the chosen SAF folder on the `vst-export`
  thread, never on main.
* **foreground service** (`EngineService`): held for the duration of a batch together with a
  PARTIAL_WAKE_LOCK so the process is not frozen mid-render.

## 6. Native decoder

`libvstdecode.so` (`cpp/`) is a dependency-free decoder for the formats the platform codecs
handle badly:

| File | Scope |
| --- | --- |
| `nd_decode.c` | entry points, WAV writer, FLAC via dr_flac, ALAC via `nd_alac` |
| `nd_alac.c` | Apple Lossless bitstream decoder (bit-exact with the reference) |
| `nd_mp4.c` | minimal MP4/M4A box parser (moov, stsz/stsc/stco/stts) |
| `jni_bridge.c` | `NativeDecode` entry points |
| `pc/` | desktop test harness (`ndpctest`) built from the same sources |

The FLAC path is only used when the platform extractor would silently down-convert 24-bit audio.

## 7. Library and ReplayGain

* `LibraryRef` indexes SAF trees (roots → tracks by `rootId/relPath`), caches tags in
  `files/tags-cache.json` and never stores cover art there.
* `Tags` reads ID3/FLAC/MP4 metadata directly from a `Seekable` abstraction that works on both
  `File` and `ContentResolver` pipes.
* `Loudness` implements EBU R128 (K-weighting, gated integrated loudness, true peak).
  `ReplayGain` scans albums or selections through it, writes tags via `RgTags` and caches results
  in `files/replaygain.json` (`RgStore`, bounded to 20 000 entries).

## 8. On-disk layout (app-private)

```
files/glibc/                 unpacked rootfs + wine + payload (the guest environment)
files/glibc/opt/vstenv/work/ in/ out/ seg/ — render scratch space
files/diag.log               rolling diagnostics (4 MB)
files/boot.conf              debug-only overrides (debuggable builds only)
files/playlists.json         atomic tmp+fsync+rename writes
files/tags-cache.json        SAF tag cache
files/replaygain.json        ReplayGain measurements
cacheDir/rg/, cacheDir/rgstage/  decode staging, deleted after each scan
```
