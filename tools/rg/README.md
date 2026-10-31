# ReplayGain core verification (`tools/rg`)

PC-side harness for the pure-Java ReplayGain core

- `android/app/src/main/java/com/vstplayer/core/Loudness.java` (incl. `AlbumAnalyzer`)
- `android/app/src/main/java/com/vstplayer/core/RgTags.java` (FLAC, WAV ID3 and MP4 `----`)
- `android/app/src/main/java/com/vstplayer/core/RgStore.java`
- `android/app/src/main/java/com/vstplayer/core/ReplayGain.java` (compile + API check only)

Nothing here is part of the Android build. `verify.py` copies the three dependency-free sources
plus `src/RgCheck.java` into `build/` and compiles them with plain `javac` (no Gradle), so the
same code runs on the PC and on the device.

`ReplayGain.java` needs `android.*`, so it is compiled separately against the signature mirrors
in `stubs/` (`android.content`, `android.media`, `android.os` and the small core classes it
calls) and its public API is then checked reflectively by `stubs/com/vstplayer/core/ApiCheck.java`.
Those stubs are a signature mirror for the compile check only - the Gradle build stays authoritative.

## Run

```
python tools/rg/verify.py
```

Needs JDK 17+ (`javac`/`java` on PATH) and ffmpeg/ffprobe at the paths in `verify.py`
(`D:\ASUS\Downloads\NVENCForge\tools`). Exit code 0 means every check passed.
`work/` and `build/` are disposable and regenerated; redirect stdout to keep a log
(`python tools/rg/verify.py > tools/rg/work/verify.log 2>&1`).

The script is read-only with respect to the repository; it only writes under `tools/rg/`.

## What is checked (274 checks)

1. **Build**: `javac -Xlint:all` produces no warnings, and the three classes compile with
   `--release 8` (no `java.nio.file`/`java.time`/other post-Java-8 APIs that minSdk 24 lacks).
2. **K-weighting coefficients** at 44.1/48/88.2/96/192 kHz, with the 48 kHz values compared to the
   BS.1770-4 published table (max error < 1e-6). Coefficients come from the analogue prototypes
   (f0 1681.974 Hz, +4 dB shelf, Q 0.7072; RLB high pass f0 38.1355 Hz, Q 0.50033), so every rate
   is a real re-derivation, not a resampled table.
3. **Integrated loudness vs ffmpeg** for 19 files: 44.1/48/96/192 kHz, 16/24 bit, mono/stereo,
   1 kHz sine, pink noise, brown noise, 5.1, quad (explicit speaker mask), LFE-only, silence,
   a -84 dBFS sine and two files shorter than one 400 ms block.
   Reference: `ebur128=metadata=true` frame tags (`lavfi.r128.I`, 3 decimals, native rate).
4. **Sample peak vs ffmpeg astats** (`Peak level dB` of the "Overall" section, 6 decimals).
5. **True peak** vs `loudnorm=print_format=json` `input_tp` (2 decimals).
6. **Tag round trips** for WAV 16/24/32 bit and FLAC (plain, and with an existing VORBIS_COMMENT,
   PICTURE block and TITLE): decoded-PCM md5 before/after, ffprobe visibility, our own reader,
   idempotent rewrite, exactly one `id3 ` chunk, consistent RIFF size.
7. **Existing tags + unrelated ID3 frames are preserved** when writing WAV (TIT2 survives, an
   existing REPLAYGAIN_TRACK_GAIN is merged, not duplicated).
8. **Edge cases**: odd-sized chunk + pad byte, RIFF size field lying (`0`), truncated data
   chunk (analysable, refused for writing), float WAV (explicit refusal, file untouched).
9. **RgStore**: put/save/load/reload values, size and mtime invalidation, corrupt JSON degrades to
   an empty cache.
10. **Cancel** stops early, leaves `lufs = NaN` and `cancelled = true`.

11. **ReplayGain compile + API**: `ReplayGain.java` compiles with `-Xlint:all` against the Android
    signature stubs, and `ApiCheck` asserts by reflection that the legacy `scan(...)`, the
    mode-aware `scan(..., Mode, ...)`, the mode-aware `scanAsync`, the three `Mode` values,
    `RgTags.readMp4/writeMp4/isMp4/albumKey` and `Loudness.AlbumAnalyzer.add/finish/files/cancelled`
    all exist with the expected signatures.
12. **M4A free form tags** for six containers (ALAC 16/24 bit stereo, ALAC 24 bit stereo faststart,
    ALAC 16 bit 5.1 faststart, ALAC 24 bit 5.1, AAC 16 bit stereo): decoded PCM md5 before/after,
    `stco`/`co64` bytes identical, exactly one `moov`, the four `----` items with
    `mean/name/data` (type 1, locale 0), ffprobe tags and stream list, repeated shorter/longer
    rewrites, idempotency, and that album fields survive a track-only merge.
13. **M4A layout edge cases**: in-place padding (free child and handler name) of a hand-built
    moov-before-mdat file keeps the file size, mdat offset and `stco` bytes; growing a faststart
    file relocates moov behind mdat without moving mdat; an 822 KB and a 17.4 MB `covr` are copied
    byte for byte (the 17.4 MB one under `-Xmx12m`); a file without `moov` is refused and left
    byte identical, and reading it degrades to "no tags".
14. **Album modes**: grouping keys (album artist + album, album fallback, unknown album), album
    loudness against ffmpeg ebur128 on the byte-concatenated WAV files (|dLUFS| <= 0.1, no inserted
    silence), single-file album == `analyzeWav`, the album value is not the mean of the track
    values, mixed sample rates stay finite, cancellation yields NaN, and an 8 minute album is
    measured under `-Xmx20m` (2.0 MB heap peak for 87.9 MB of PCM).


Reference precision: `ebur128` prints the summary with one decimal, which is why the harness
takes `lavfi.r128.I` from the filter's frame metadata instead (3 decimals). Sound-file
`I` values may legitimately differ by 0.01 LU because ffmpeg and this code round blocks
independently.

## Measured results (last run)

| material | ref LUFS | our LUFS | dLUFS | ref peak | our peak | dPeak | ref TP | our TP | dTP |
|---|---|---|---|---|---|---|---|---|---|
| sine 44100/16/st | -9.03 | -9.02 | +0.01 | -9.03 | -9.03 | 0.00 | -9.03 | -9.03 | 0.00 |
| sine 44100/24/st | -9.03 | -9.02 | +0.01 | -9.03 | -9.03 | 0.00 | -9.03 | -9.03 | 0.00 |
| sine 48000/16/st | -9.03 | -9.03 | 0.00 | -9.03 | -9.03 | 0.00 | -9.03 | -9.03 | 0.00 |
| sine 48000/24/st | -9.03 | -9.03 | 0.00 | -9.03 | -9.03 | 0.00 | -9.03 | -9.03 | 0.00 |
| sine 48000/16/mono | -9.03 | -9.03 | 0.00 | -6.02 | -6.02 | 0.00 | -6.02 | -6.02 | 0.00 |
| sine 48000/24/mono | -9.03 | -9.03 | 0.00 | -6.02 | -6.02 | 0.00 | -6.02 | -6.02 | 0.00 |
| sine 96000/16/st | -9.05 | -9.04 | +0.01 | -9.03 | -9.03 | 0.00 | -9.03 | -9.03 | 0.00 |
| sine 96000/24/st | -9.05 | -9.04 | +0.01 | -9.03 | -9.03 | 0.00 | -9.03 | -9.03 | 0.00 |
| sine 192000/16/st | -9.06 | -9.05 | +0.01 | -9.03 | -9.03 | 0.00 | -9.03 | -9.03 | 0.00 |
| pink 48000/16/st | -26.67 | -26.66 | 0.00 | -15.45 | -15.45 | 0.00 | -15.41 | -15.39 | +0.02 |
| pink 44100/24/mono | -22.64 | -22.64 | 0.00 | -9.33 | -9.33 | 0.00 | -9.33 | -9.33 | 0.00 |
| brown 96000/24/st | -20.56 | -20.56 | 0.00 | -9.33 | -9.33 | 0.00 | -9.28 | -9.27 | +0.01 |
| 5.1 48000/24 (mask 0x3F) | -1.38 | -1.38 | 0.00 | -6.02 | -6.02 | 0.00 | -6.02 | -6.02 | 0.00 |
| quad 48000/16 (mask 0x33) | -2.20 | -2.19 | +0.01 | -6.02 | -6.02 | 0.00 | -6.02 | -6.02 | 0.00 |
| LFE-only 5.1 48000/16 | gated out | NaN | - | -6.02 | -6.02 | 0.00 | -6.02 | -6.02 | 0.00 |
| quiet sine -84 dBFS | -70.00 (gate floor) | NaN | - | -84.29 | -84.29 | 0.00 | -83.62 | -83.76 | -0.14 |
| silence 48000/16/st | -70.00 (gate floor) | NaN | - | -inf | -inf | 0.00 | -inf | -inf | 0.00 |
| silence 44100/24/mono | -70.00 (gate floor) | NaN | - | -inf | -inf | 0.00 | -inf | -inf | 0.00 |
| short 0.20 s 48000/16 | -70.00 (gate floor) | NaN | - | -9.03 | -9.03 | 0.00 | -9.03 | -9.03 | 0.00 |
| short 0.39 s 44100/24 | -inf | NaN | - | -6.02 | -6.02 | 0.00 | -6.02 | -6.02 | 0.00 |

Tolerances enforced by the harness: |dLUFS| <= 0.1, |dPeak| <= 0.05 dB, |dTP| <= 0.35 dB,
and exact NaN/-inf agreement for gated material.

### Gate behaviour (silence, sub-gate, short files)

ffmpeg's `ebur128` reports `-70.00 LUFS` (the absolute gate value, not a measurement)
when every 400 ms block is gated out, and `-inf` when the file is too short for one block. This
implementation returns `NaN` in both cases: `Result.silent()` is true, `gainDb()` is
0, and the caller should skip writing a gain tag. That is the honest BS.1770 answer - the input has
no measurable loudness, so there is nothing to normalise to.

### True peak

Implemented, 4x oversampled, opt-in (`analyzeWav(f, p, true)`; the sample-peak-only overload
does not pay for it). Method: 12 taps per phase, 48 tap Blackman-windowed sinc, each phase
DC-normalised, max over the four phases and the raw sample. Agreement with ffmpeg's
libswresample-based true peak is 0.00-0.02 dB on normal material and 0.14 dB on a -84 dBFS sine;
it is an approximation, not a bit-exact clone of ffmpeg's filter.

`peakDbfs` (always computed) is the sample peak: max |sample| / full scale of the
container, i.e. 0 dBFS = 2^(bits-1).

## Tag write verification

Decoded-PCM md5 (`ffmpeg -i file -map 0:a -f md5 -`) is identical before and after tagging:

| file | decoded PCM md5 |
|---|---|
| tags_w16.wav (WAV 16, tagged) | 67f61e2e38dd8fc2e0c7181b348d88d1 |
| tags_w24.wav (WAV 24, tagged) | 71b8c713d3dec115b472270c31ed16e8 |
| tags_w32.wav (WAV 32, tagged) | 71b8c713d3dec115b472270c31ed16e8 |
| tags_plain.flac (FLAC, tagged) | 67f61e2e38dd8fc2e0c7181b348d88d1 |
| tags_existing.flac (+PICTURE, tagged) | 99a0c11222b5997e18b10fc74d2276bb |
| id3_existing.wav (merged ID3, tagged) | 67f61e2e38dd8fc2e0c7181b348d88d1 |
| big192.wav (138 MB, 192k/24, tagged with -Xmx32m) | 3feee910c0a14f5870a1707a98015bba |

ffprobe reads the new tags back in all cases, e.g.

```
ffprobe -show_entries format_tags tags_existing.flac
  REPLAYGAIN_ALBUM_GAIN=-3.00 dB   (pre-existing, preserved)
  REPLAYGAIN_TRACK_GAIN=2.25 dB   (written)
  REPLAYGAIN_TRACK_PEAK=0.9876    (written)
```

The 138 MB case is deliberate: it proves the WAV rewrite streams chunk by chunk (the whole file is
never buffered) and stays inside a 32 MB heap.

## M4A / MP4 tag verification

`RgTags.writeMp4` writes one `----` free form item per key under `moov/udta/meta/ilst` with
`mean = com.apple.iTunes`, `name = REPLAYGAIN_*` and one `data` child typed UTF-8 (type 1,
locale 0) - the layout foobar2000 and MP3TAG use, and the one ffmpeg promotes to "Replay Gain"
stream side data. `udta`/`meta`/`ilst` are created when the file has none.

Only the `moov` box is rebuilt; `mdat` and every sample table box is copied byte for byte. When
`moov` sits in front of `mdat` (faststart) the new metadata is padded to the old `moov` size
(a trailing `free` child, or the handler name for a 1..7 byte remainder) so `stco` never has to
move. When the metadata does not fit, `moov` is relocated behind the media data and its old slot
is filled with an equally sized `free` child, which leaves every absolute offset valid as well.

| container | layout | decoded PCM md5 (identical before / after) |
|---|---|---|
| ALAC 16 bit stereo | moov after mdat | 0799e6b1e459d60cd3b5ee88a40e2a79 |
| ALAC 24 bit stereo | moov after mdat | a539fc330727d3825a81923f1f18f440 |
| ALAC 24 bit stereo | faststart | a539fc330727d3825a81923f1f18f440 |
| ALAC 16 bit 5.1 | faststart | aa5e094a307cbe85ef6f09d7653575b9 |
| ALAC 24 bit 5.1 | moov after mdat | aa5e094a307cbe85ef6f09d7653575b9 |
| AAC 16 bit stereo | moov after mdat | 1d5ac4daeef1c1c876640ec65fa58075 |

ffprobe reads the tags back (`format_tags`), still lists every stream (audio, cover art) and
ffmpeg adds `side_data_list: [{side_data_type: "Replay Gain"}]` to the audio stream. The harness
also asserts that `stco`/`co64` is byte identical, that exactly one `moov` remains, and that
repeated writes (shorter, longer, identical) are stable.

## Album loudness ("whole group as one stream")

`Loudness.AlbumAnalyzer` feeds consecutive WAV files into one K-weighting run: filter state, the
100 ms sub block accumulator and the overlapping 400 ms blocks carry over the file boundary, no
silence is inserted, and both gates are applied once to the blocks of the whole group. Reference:
ffmpeg `ebur128` on the `data` chunks of the files concatenated byte for byte.

| group | material | ref LUFS | our LUFS | dLUFS | peak |
|---|---|---|---|---|---|
| alb_st16 | 3 tracks 4.0 / 3.3 / 2.7 s, 48k/16/stereo | -8.38 | -8.38 | 0.00 | match |
| alb_mono24 | 2 tracks 3.9 / 2.2 s, 44.1k/24/mono | -7.67 | -7.67 | 0.00 | match |
| alb_51_24 | 3 tracks 3.5 / 2.5 / 4.1 s, 48k/24/5.1 | +0.55 | +0.56 | +0.01 | match |

Also checked: one file through the album analyser equals `analyzeWav` exactly; the album result
differs from the arithmetic mean of the per-track loudness by more than 0.5 LU (the gating is
not an average); the album duration is the exact sum of the tracks; mixed sample rates still
yield one finite value; cancellation yields NaN.

Grouping (`RgTags.albumKey`): key = album artist + NUL + album, so the pair defines a group.
An empty album artist falls back to the album alone; when both are empty every file lands in
the single `UNKNOWN_ALBUM` group. `Mode.ALL_AS_ONE_ALBUM` ignores the tags and uses one group
for the whole list. `Mode.ALBUM` always measures the audio (an existing track tag or a warm
cache is not enough for an album value) and then writes `TRACK_*` and the shared `ALBUM_*` to
every file of the group.

## Streaming memory

Measured with the harness' heap sampler; the JVM heap cap is enforced by `-Xmx`:

| scenario | material | heap cap | measured heap peak |
|---|---|---|---|
| album scan (8 x 60 s, 48k/16/stereo) | 87.9 MB of PCM | -Xmx20m | 2.0 MB |
| M4A tag write with 17.4 MB `covr` | 18.3 MB m4a | -Xmx12m | 2.5 MB |
| M4A tag write with 822 KB `covr` | 0.9 MB m4a | -Xmx16m | 2.6 MB |

The album analyser keeps one double per landed 400 ms block (about 10 per second of audio) plus
the filter state; the MP4 writer copies `mdat`, cover art and every preserved item as byte
ranges with a 64 KB copy buffer.

## Known limitations

- **Float WAV is refused**: `writeWav` throws `IOException` ("IEEE float") and leaves the
  file untouched. `Loudness.analyzeWav` also refuses non-integer PCM. 8-bit PCM is not
  handled either (task scope: 16/24/32 bit).
- **WAV channel layout**: `WAVE_FORMAT_EXTENSIBLE` speaker masks are honoured exactly. Without a
  mask the layout is guessed from the channel count (1, 2, 3 = L R C, 4 = quad, 5, 6 = 5.1,
  8 = 7.1); a 4-channel WAV with no mask is ambiguous (ffmpeg calls it 4.0 = FL FR FC BC, this
  code calls it quad) and unknown counts get weight 1.0 with a note on stderr. LFE is always
  excluded. Back centre counts as a surround (1.41).
- **WAV ID3**: v2.3/2.4 frames are preserved on write; an existing obsolete v2.2 tag is replaced
  (its 3-character frame ids cannot be re-emitted as v2.3). ID3 is capped at 16 MiB.
- **FLAC metadata is rebuilt in memory** (metadata only, audio is streamed). A pathological
  multi-megabyte PICTURE block is therefore bounded by available heap, not by file size.
- **Short files**: less than 400 ms of audio yields no block, so `lufs` is NaN. The final
  partial 100 ms sub-block is discarded (same as libebur128).
- **Album values are not cached**: `RgStore` still stores only the per-track measurement;
  the album gain/peak live in the file tags (and in `Summary`). `Mode.ALBUM` therefore always rescans.
- **Album order**: there are no track numbers in `Tags.Info`, so the order of the passed file list
  defines the concatenated stream (and therefore the boundary blocks).
- **MP4 scope**: only the standard `moov/udta/meta/ilst` layout is written. Fragmented MP4
  (`moof`) and encrypted files are not covered by tests; a file without `moov` is refused. The
  `free`-atom padding and the relocation path both preserve `stco`/`co64` (and `iloc`) offsets by never
  moving `mdat`; they are exercised on 32-bit `stco` material.
- **Big covers**: a multi-megabyte `covr` is copied as a range, not loaded; the rest of the
  metadata is walked box by box, so a pathological `ilst` with tens of thousands of items is slow
  but bounded in memory.
- **ReplayGain compile check uses stubs**: `stubs/` mirrors the signatures `ReplayGain` uses; it is a
  compile smoke test, the Gradle build remains the authority.
- **Native rename semantics**: writes use temp file + rename in the same directory; on filesystems
  that refuse to replace an existing file, the finished temp is copied over the target. If even
  that fails, the temp file is kept and its path logged to stderr rather than deleting the only
  complete copy.
