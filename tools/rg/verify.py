#!/usr/bin/env python
"""ReplayGain core verification: Loudness vs ffmpeg ebur128/loudnorm/astats, RgTags round trip, RgStore.

Run from anywhere:  python tools/rg/verify.py
Exit code 0 only when every check passes. Uses only the Python standard library.
"""
import json
import math
import os
import shutil
import struct
import subprocess
import sys

RG = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(os.path.dirname(RG))
CORE = os.path.join(PROJ, "android", "app", "src", "main", "java", "com", "vstplayer", "core")
WORK = os.path.join(RG, "work")
BUILD = os.path.join(RG, "build")
CLS = os.path.join(BUILD, "classes")
SRC = os.path.join(BUILD, "src", "com", "vstplayer", "core")
TAGDIR = os.path.join(WORK, "tags")

FF = r"D:\ASUS\Downloads\NVENCForge\tools\ffmpeg.exe"
FP = r"D:\ASUS\Downloads\NVENCForge\tools\ffprobe.exe"

FAILS = []
CHECKS = [0]


def run(cmd, cwd=None):
    p = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")


def check(ok, label, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILS.append(label + (": " + detail if detail else ""))
    print(("  PASS " if ok else "  FAIL ") + label + ((" -- " + detail) if (detail and not ok) else ""))
    return ok


def ff(args, cwd=None):
    rc, out, err = run([FF, "-hide_banner", "-loglevel", "error", "-y"] + args, cwd=cwd)
    if rc != 0:
        raise RuntimeError("ffmpeg failed: " + " ".join(args) + "\n" + err[-2000:])
    return out + err


def ffrun(args, cwd=None):
    return run([FF, "-hide_banner", "-nostats"] + args, cwd=cwd)


def java(args, cwd=WORK, xmx=None):
    cmd = ["java"] + (["-Xmx" + xmx] if xmx else []) + ["-cp", CLS,
                                                        "com.vstplayer.core.RgCheck"] + args
    rc, out, err = run(cmd, cwd=cwd)
    return rc, out, err

def kv(line, prefix):
    out = {}
    for token in line[len(prefix):].split():
        k, _, v = token.partition("=")
        out[k] = v
    return out

def parse_album(out):
    for line in out.splitlines():
        if line.startswith("ALBUM "):
            return kv(line, "ALBUM ")
    raise RuntimeError("RgCheck produced no ALBUM line: " + out[:400])


# ------------------------------------------------------------------ build

def build():
    print("== build (javac, no gradle)")
    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(SRC)
    os.makedirs(CLS)
    for name in ("Loudness.java", "RgTags.java", "RgStore.java"):
        shutil.copy(os.path.join(CORE, name), SRC)
    shutil.copy(os.path.join(RG, "src", "RgCheck.java"), SRC)
    files = [os.path.join(SRC, n) for n in
             ("Loudness.java", "RgTags.java", "RgStore.java", "RgCheck.java")]
    rc, out, err = run(["javac", "-J-Duser.language=en", "-encoding", "UTF-8", "-Xlint:all",
                        "-d", CLS] + files)
    check(rc == 0 and not out.strip(), "javac clean (no warnings)", (out + err)[-1500:])
    if rc != 0:
        sys.exit(1)


STUBS = os.path.join(RG, "stubs")


def build_replaygain():
    print("\n== compile check: ReplayGain against Android signature stubs")
    root = os.path.join(BUILD, "stub")
    src = os.path.join(root, "src")
    cls = os.path.join(root, "classes")
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(src)
    os.makedirs(cls)
    files = []
    for dirpath, _dirnames, names in os.walk(STUBS):
        for name in names:
            if not name.endswith(".java"):
                continue
            rel = os.path.relpath(os.path.join(dirpath, name), STUBS)
            dst = os.path.join(src, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy(os.path.join(dirpath, name), dst)
            files.append(dst)
    core = os.path.join(src, "com", "vstplayer", "core")
    for name in ("Loudness.java", "RgTags.java", "RgStore.java", "ReplayGain.java"):
        dst = os.path.join(core, name)
        shutil.copy(os.path.join(CORE, name), dst)
        files.append(dst)
    rc, out, err = run(["javac", "-J-Duser.language=en", "-encoding", "UTF-8", "-Xlint:all",
                        "-d", cls] + files)
    check(rc == 0 and not (out + err).strip(), "ReplayGain + stubs compile cleanly",
          (out + err)[-1500:])
    if rc != 0:
        return
    rc, out, err = run(["java", "-cp", cls, "com.vstplayer.core.ApiCheck"])
    check(rc == 0 and "API OK" in out, "public API shape (scan modes, MP4 writers, album)",
          out + err)


# ------------------------------------------------------------------ material

MATERIAL = [
    # name, rate, bits, channels, ffmpeg source args
    ("sine_44100_16_st", 44100, 16, 2, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=44100:duration=6", "-af", "volume=4"]),
    ("sine_44100_24_st", 44100, 24, 2, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=44100:duration=6", "-af", "volume=4"]),
    ("sine_48000_16_st", 48000, 16, 2, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=48000:duration=6", "-af", "volume=4"]),
    ("sine_48000_24_st", 48000, 24, 2, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=48000:duration=6", "-af", "volume=4"]),
    ("sine_48000_16_mono", 48000, 16, 1, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=48000:duration=6", "-ac", "1", "-af", "volume=4"]),
    ("sine_48000_24_mono", 48000, 24, 1, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=48000:duration=6", "-ac", "1", "-af", "volume=4"]),
    ("sine_96000_16_st", 96000, 16, 2, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=96000:duration=6", "-af", "volume=4"]),
    ("sine_96000_24_st", 96000, 24, 2, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=96000:duration=6", "-af", "volume=4"]),
    ("sine_192000_16_st", 192000, 16, 2, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=192000:duration=6", "-af", "volume=4"]),
    ("pink_48000_16_st", 48000, 16, 2, ["-f", "lavfi", "-i",
     "anoisesrc=color=pink:sample_rate=48000:amplitude=0.25:duration=6"]),
    ("pink_44100_24_mono", 44100, 24, 1, ["-f", "lavfi", "-i",
     "anoisesrc=color=pink:sample_rate=44100:amplitude=0.4:duration=6",
     "-af", "aformat=channel_layouts=mono"]),
    ("brown_96000_24_st", 96000, 24, 2, ["-f", "lavfi", "-i",
     "anoisesrc=color=brown:sample_rate=96000:amplitude=0.5:duration=6",
     "-af", "aformat=channel_layouts=stereo"]),
    ("quiet_sine_48000_16_st", 48000, 16, 2, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=48000:duration=4", "-af", "volume=0.0008"]),
    ("silence_48000_16_st", 48000, 16, 2, ["-f", "lavfi", "-i",
     "anullsrc=r=48000:cl=stereo", "-t", "4"]),
    ("silence_44100_24_mono", 44100, 24, 1, ["-f", "lavfi", "-i",
     "anullsrc=r=44100:cl=mono", "-t", "4"]),
    ("short_020s_48000_16_st", 48000, 16, 2, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=48000:duration=0.2", "-af", "volume=4"]),
    ("short_039s_44100_24_mono", 44100, 24, 1, ["-f", "lavfi", "-i",
     "sine=frequency=1000:sample_rate=44100:duration=0.39", "-ac", "1", "-af", "volume=4"]),
    # multichannel material is written by python below: ffmpeg's pan filter does not give
    # predictable per-channel content, and explicit PCM is a stronger check
    ("quad_48000_16", 48000, 16, 4, None),
    ("fiveone_48000_24", 48000, 24, 6, None),
    ("lfe_only_48000_16", 48000, 16, 6, None),
]

MULTI = {
    # name: (per-channel amplitude of a 1 kHz sine, channel mask)
    # WAV order: FL FR FC LFE BL BR / quad is FL FR BL BR
    "quad_48000_16": ([0.5, 0.5, 0.5, 0.5], 0x33),
    "fiveone_48000_24": ([0.5, 0.5, 0.5, 0.5, 0.5, 0.5], 0x3F),
    "lfe_only_48000_16": ([0.0, 0.0, 0.0, 0.5, 0.0, 0.0], 0x3F),
}


def gen():
    print("== generate material in tools/rg/work")
    if not os.path.isdir(WORK):
        os.makedirs(WORK)
    for name, rate, bits, ch, args in MATERIAL:
        path = os.path.join(WORK, name + ".wav")
        if os.path.isfile(path):
            continue
        if args is None:
            amps, mask = MULTI[name]
            write_multi_wav(path, rate, bits, ch, amps, mask)
            continue
        codec = {16: "pcm_s16le", 24: "pcm_s24le"}[bits]
        ff(args + ["-ar", str(rate), "-ac", str(ch), "-c:a", codec, name + ".wav"], cwd=WORK)


def write_multi_wav(path, rate, bits, ch, amps, mask, seconds=4.0, freq=1000.0):
    """Deterministic multichannel integer PCM WAV (fmt tag 1, channel count implies layout)."""
    n = int(rate * seconds)
    bps = bits // 8
    scale = (1 << (bits - 1)) - 1
    step = 2.0 * math.pi * freq / rate
    out = bytearray()
    for i in range(n):
        s = math.sin(step * i)
        for c in range(ch):
            v = int(round(scale * amps[c] * s))
            if bits == 16:
                out += struct.pack("<h", v)
            else:
                out += (v & 0xFFFFFF).to_bytes(3, "little")
    block = ch * bps
    # WAVE_FORMAT_EXTENSIBLE so the speaker layout (and therefore the BS.1770 weights) is explicit
    pmc_guid = bytes([0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
                      0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71])
    fmt = (struct.pack("<HHIIHH", 0xFFFE, ch, rate, rate * block, block, bits)
           + struct.pack("<HHI", 22, bits, mask) + pmc_guid)
    with open(path, "wb") as f:
        f.write(b"RIFF" + struct.pack("<I", 4 + 8 + len(fmt) + 8 + len(out)) + b"WAVE")
        f.write(b"fmt " + struct.pack("<I", len(fmt)) + fmt)
        f.write(b"data" + struct.pack("<I", len(out)))
        f.write(out)


# ------------------------------------------------------------------ references

def ref_lufs(name):
    """Integrated loudness from ebur128 metadata (3 decimals, native rate)."""
    rc, out, err = run([FP, "-v", "error", "-f", "lavfi", "-i",
                        "amovie=" + name + ",ebur128=metadata=true:peak=sample+true",
                        "-show_frames", "-show_entries", "frame_tags", "-of", "json"], cwd=WORK)
    if rc != 0 or not out.strip():
        return None, "ebur128 metadata failed: " + err[-300:]
    frames = json.loads(out).get("frames", [])
    if not frames:
        return None, "no frames"
    # the last frame can be a flush frame without metadata, so take the last one that has it
    for frame in reversed(frames):
        v = frame.get("tags", {}).get("lavfi.r128.I")
        if v is not None:
            return parse_num(v), None
    return None, "no lavfi.r128.I"


def ref_peak(name):
    rc, out, err = ffrun(["-i", name, "-af", "astats=metadata=0", "-f", "null", "-"], cwd=WORK)
    vals = []
    for line in (out + err).splitlines():
        if "Peak level dB:" in line:
            vals.append(line.split("Peak level dB:")[1].strip())
    if not vals:
        return None, "astats gave no peak"
    return parse_num(vals[-1]), None


def ref_truepeak(name):
    rc, out, err = ffrun(["-i", name, "-af", "loudnorm=print_format=json", "-f", "null", "-"], cwd=WORK)
    text = out + err
    i = text.rfind("{")
    if i < 0:
        return None, "loudnorm gave no json"
    j = text.rfind("}")
    if j < i:
        return None, "loudnorm json truncated"
    try:
        data = json.loads(text[i:j + 1])
    except ValueError as e:
        return None, "loudnorm json: " + str(e)
    return parse_num(data.get("input_tp")), None


def diff(a, b):
    if a != a or b != b or math.isinf(a) or math.isinf(b):
        return float("nan")
    return a - b


def N(pair):
    """UNKNOWN/None from a reference probe becomes NaN, which the comparators treat as 'no value'."""
    v = pair[0] if isinstance(pair, tuple) else pair
    return float("nan") if v is None else v


def parse_num(s):
    if s is None:
        return float("nan")
    s = str(s).strip().lower()
    if s in ("-inf", "-infinity"):
        return float("-inf")
    if s in ("inf", "infinity"):
        return float("inf")
    if s in ("nan", "-nan", ""):
        return float("nan")
    try:
        return float(s)
    except ValueError:
        return float("nan")


def our(name, true_peak=True, cancel=False):
    args = ["lufs", name]
    if true_peak:
        args.append("--true")
    if cancel:
        args.append("--cancel")
    rc, out, err = java(args)
    for line in out.splitlines():
        if line.startswith("RESULT "):
            d = {}
            for token in line[7:].split():
                k, _, v = token.partition("=")
                d[k] = v
            return d, err
    raise RuntimeError("RgCheck produced no RESULT: " + out + err)


# ------------------------------------------------------------------ wav helpers

def wav_chunks(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("not RIFF/WAVE: " + path)
    riff_size = struct.unpack("<I", data[4:8])[0]
    pos = 12
    chunks = []
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        size = struct.unpack("<I", data[pos + 4:pos + 8])[0]
        body = pos + 8
        if body + size > len(data):
            chunks.append((cid, size, body, "TRUNCATED"))
            break
        chunks.append((cid, size, body, "ok"))
        pos = body + size + (size & 1)
    return riff_size, len(data), chunks, data


def md5_pcm(path):
    rc, out, err = run([FF, "-hide_banner", "-loglevel", "error", "-i", path,
                        "-map", "0:a", "-f", "md5", "-"])
    out = out.strip()
    if not out.startswith("MD5="):
        raise RuntimeError("cannot decode %s: %s" % (path, err.strip()[-400:]))
    return out, err


def probe_tags(path):
    rc, out, err = run([FP, "-v", "error", "-show_entries", "format_tags", "-of", "json", path])
    try:
        tags = json.loads(out)["format"].get("tags", {})
    except Exception:
        return {}
    return {k.upper(): v for k, v in tags.items()}


# ------------------------------------------------------------------ loudness table

def loudness_table():
    print("\n== integrated loudness / peak vs ffmpeg")
    hdr = ("material", "ref LUFS", "our LUFS", "dLUFS", "ref peak", "our peak", "dPeak",
           "refTP", "ourTP", "dTP")
    rows = []
    for name, rate, bits, ch, _args in MATERIAL:
        fname = name + ".wav"
        rl = N(ref_lufs(fname))
        rp = N(ref_peak(fname))
        rt = N(ref_truepeak(fname))
        try:
            d, jerr = our(fname)
        except Exception as e:
            FAILS.append("java failed for " + fname + ": " + str(e))
            print("  FAIL java on " + fname + ": " + str(e))
            continue
        ol = parse_num(d["lufs"])
        op = parse_num(d["peakDb"])
        ot = parse_num(d["trueDb"])
        dl = diff(ol, rl)
        dp = diff(op, rp)
        dt = diff(ot, rt)
        rows.append((name, rl, ol, dl, rp, op, dp, rt, ot, dt, d))
        # tolerance only where a finite reference exists; silent/short files must agree on NaN
        # ffmpeg reports the -70 LUFS gate floor (or nothing) when every block is gated out;
        # our meter returns NaN then. Both mean "no measurable integrated loudness".
        ref_gated = (rl != rl) or rl <= -69.99
        if ref_gated:
            check(ol != ol, "below-gate agreement " + name, "ref=%s ours=%s" % (rl, ol))
        elif ol != ol:
            check(False, "|dLUFS|<=0.1 " + name, "ours=NaN ref=%.2f" % rl)
        else:
            check(abs(dl) <= 0.1, "|dLUFS|<=0.1 " + name, "%.3f" % dl)
        if math.isinf(rp) or math.isinf(op):
            check(rp == op, "|dPeak|<=0.05 " + name, "ref=%s ours=%s" % (rp, op))
        elif rp == rp and op == op:
            check(abs(dp) <= 0.05, "|dPeak|<=0.05 " + name, "%.3f" % dp)
        else:
            check((rp != rp) and (op != op), "peak agreement " + name, "ref=%s ours=%s" % (rp, op))
        if math.isinf(rt) or math.isinf(ot):
            check(rt == ot, "|dTP|<=0.35 " + name, "ref=%s ours=%s" % (rt, ot))
        elif rt == rt and ot == ot:
            check(abs(dt) <= 0.35, "|dTP|<=0.35 " + name, "%.3f" % dt)
        else:
            check((rt != rt) and (ot != ot), "true peak agreement " + name,
                  "ref=%s ours=%s" % (rt, ot))
        if d.get("cancelled") != "false":
            check(False, "not cancelled " + name)
    print()
    fmt = "%-24s %8s %8s %7s %8s %8s %7s %8s %8s %7s"
    print(fmt % hdr)
    for (name, rl, ol, dl, rp, op, dp, rt, ot, dt, d) in rows:
        print(fmt % (name,
                     s(rl), s(ol), s(dl), s(rp), s(op), s(dp), s(rt), s(ot), s(dt)))
    return rows


def s(v):
    if v is None:
        return "-"
    if v != v:
        return "NaN"
    if math.isinf(v):
        return "-inf" if v < 0 else "inf"
    return "%.2f" % v


# ------------------------------------------------------------------ tag tests

def tag_tests(rows):
    print("\n== tag write / read round trip")
    if os.path.isdir(TAGDIR):
        shutil.rmtree(TAGDIR)
    os.makedirs(TAGDIR)
    base = os.path.join(WORK, "sine_48000_16_st.wav")

    # --- WAV 16 bit -------------------------------------------------------
    wav16 = os.path.join(WORK, "tags_w16.wav")
    shutil.copy(base, wav16)
    before, _ = md5_pcm(wav16)
    riff0, size0, chunks0, _ = wav_chunks(wav16)
    rc, out, err = java(["writetags", "tags_w16.wav", "REPLAYGAIN_TRACK_GAIN=-7.25 dB",
                         "REPLAYGAIN_TRACK_PEAK=0.8891", "REPLAYGAIN_ALBUM_GAIN=-6.50 dB",
                         "REPLAYGAIN_ALBUM_PEAK=0.9200"])
    check(rc == 0 and "WROTE 4" in out, "writeWav 16 bit", out + err)
    after, _ = md5_pcm(wav16)
    check(before == after and before != "", "WAV16 decoded PCM md5 unchanged", before + " != " + after)
    riff1, size1, chunks1, raw = wav_chunks(wav16)
    check(riff1 == size1 - 8, "RIFF size field consistent", "%d vs %d" % (riff1, size1 - 8))
    ids = [c[0] for c in chunks1]
    check(ids.count(b"id3 ") == 1, "exactly one id3 chunk", str(ids))
    check(all(c[3] == "ok" for c in chunks1), "all chunks within file")
    tags = probe_tags(wav16)
    check(tags.get("REPLAYGAIN_TRACK_GAIN") == "-7.25 dB", "ffprobe sees TRACK_GAIN", str(tags))
    check(tags.get("REPLAYGAIN_TRACK_PEAK") == "0.8891", "ffprobe sees TRACK_PEAK", str(tags))
    rc, out, err = java(["readtags", "tags_w16.wav"])
    check("TAG REPLAYGAIN_TRACK_GAIN=-7.25 dB" in out and "HASRG true" in out,
          "readWav returns ReplayGain tags", out + err)
    # rewrite must replace, not duplicate
    rc, out, err = java(["writetags", "tags_w16.wav", "REPLAYGAIN_TRACK_GAIN=-7.25 dB"])
    riff2, size2, chunks2, _ = wav_chunks(wav16)
    check([c[0] for c in chunks2].count(b"id3 ") == 1, "rewrite keeps a single id3 chunk")
    after2, _ = md5_pcm(wav16)
    check(before == after2, "rewrite keeps decoded PCM md5")
    rc, out, err = java(["hasrg", "tags_w16.wav"])
    check("HASRG true" in out, "existing tag visible => caller can skip", out + err)

    # --- WAV 24 bit -------------------------------------------------------
    wav24 = os.path.join(WORK, "tags_w24.wav")
    shutil.copy(os.path.join(WORK, "sine_48000_24_st.wav"), wav24)
    b24, _ = md5_pcm(wav24)
    rc, out, err = java(["writetags", "tags_w24.wav", "REPLAYGAIN_TRACK_GAIN=1.75 dB",
                         "REPLAYGAIN_TRACK_PEAK=0.9512"])
    check(rc == 0, "writeWav 24 bit", out + err)
    a24, _ = md5_pcm(wav24)
    check(b24 == a24, "WAV24 decoded PCM md5 unchanged", b24 + " != " + a24)
    tags = probe_tags(wav24)
    check(tags.get("REPLAYGAIN_TRACK_GAIN") == "1.75 dB", "ffprobe sees 24 bit WAV tag", str(tags))

    # --- WAV 32 bit -------------------------------------------------------
    wav32 = os.path.join(WORK, "tags_w32.wav")
    ff(["-i", "sine_48000_24_st.wav", "-c:a", "pcm_s32le", "tags_w32.wav"], cwd=WORK)
    b32, _ = md5_pcm(wav32)
    rc, out, err = java(["writetags", "tags_w32.wav", "REPLAYGAIN_TRACK_GAIN=-2.00 dB"])
    check(rc == 0, "writeWav 32 bit", out + err)
    a32, _ = md5_pcm(wav32)
    check(b32 == a32, "WAV32 decoded PCM md5 unchanged")
    check(probe_tags(wav32).get("REPLAYGAIN_TRACK_GAIN") == "-2.00 dB",
          "ffprobe sees 32 bit WAV tag")

    # --- existing non-ReplayGain ID3 frames must survive a WAV tag write ---
    id3wav = os.path.join(WORK, "id3_existing.wav")
    shutil.copy(base, id3wav)
    insert_id3_chunk(id3wav, id3_tag([
        id3_text("TIT2", "Keep Me"),
        id3_txxx("REPLAYGAIN_TRACK_GAIN", "-1.00 dB")]))
    ib, _ = md5_pcm(id3wav)
    rc, out, err = java(["readtags", "id3_existing.wav"])
    check("TAG REPLAYGAIN_TRACK_GAIN=-1.00 dB" in out,
          "existing WAV ID3 ReplayGain is readable", out + err)
    rc, out, err = java(["writetags", "id3_existing.wav", "REPLAYGAIN_TRACK_PEAK=0.5000"])
    check(rc == 0, "writeWav merges into an existing ID3 chunk", out + err)
    ia, _ = md5_pcm(id3wav)
    check(ib == ia, "ID3 merge keeps the decoded PCM md5")
    tags = probe_tags(id3wav)
    check(tags.get("TITLE") == "Keep Me", "unrelated ID3 frame preserved", str(tags))
    check(tags.get("REPLAYGAIN_TRACK_GAIN") == "-1.00 dB",
          "existing ReplayGain frame preserved on merge", str(tags))
    check(tags.get("REPLAYGAIN_TRACK_PEAK") == "0.5000", "new ReplayGain frame added", str(tags))
    rc, out, err = java(["hasrg", "id3_existing.wav"])
    check("HASRG true" in out, "merged WAV reports complete ReplayGain", out + err)

    # --- float WAV must be refused ---------------------------------------
    f32 = os.path.join(WORK, "float32.wav")
    ff(["-i", "sine_48000_24_st.wav", "-c:a", "pcm_f32le", "float32.wav"], cwd=WORK)
    fb, _ = md5_pcm(f32)
    rc, out, err = java(["floatreject", "float32.wav"])
    check("REJECTED" in out and "float" in out.lower(), "float WAV rejected with a clear error", out + err)
    fa, _ = md5_pcm(f32)
    check(fb == fa, "float WAV left untouched")

    # --- FLAC, plain ------------------------------------------------------
    flac = os.path.join(WORK, "tags_plain.flac")
    ff(["-i", "sine_48000_16_st.wav", "-c:a", "flac", "tags_plain.flac"], cwd=WORK)
    fb, _ = md5_pcm(flac)
    rc, out, err = java(["readtags", "tags_plain.flac"])
    check("HASRG false" in out, "plain FLAC has no ReplayGain tag", out + err)
    rc, out, err = java(["writetags", "tags_plain.flac", "REPLAYGAIN_TRACK_GAIN=-7.25 dB",
                         "REPLAYGAIN_TRACK_PEAK=0.8891", "REPLAYGAIN_ALBUM_GAIN=-6.50 dB"])
    check(rc == 0 and "WROTE 3" in out, "writeFlac", out + err)
    fa, _ = md5_pcm(flac)
    check(fb == fa, "FLAC decoded PCM md5 unchanged", fb + " != " + fa)
    tags = probe_tags(flac)
    check(tags.get("REPLAYGAIN_TRACK_GAIN") == "-7.25 dB",
          "ffprobe sees FLAC TRACK_GAIN", str(tags))
    check(tags.get("REPLAYGAIN_ALBUM_GAIN") == "-6.50 dB", "ffprobe sees FLAC ALBUM_GAIN")
    rc, out, err = java(["readtags", "tags_plain.flac"])
    check("TAG REPLAYGAIN_TRACK_GAIN=-7.25 dB" in out and "HASRG true" in out,
          "readFlac returns ReplayGain tags", out + err)

    # --- FLAC with existing tags + cover art + title: merge, keep blocks, keep audio
    png = os.path.join(WORK, "cover.png")
    if not os.path.isfile(png):
        ff(["-f", "lavfi", "-i", "color=c=blue:s=64x64", "-frames:v", "1", "cover.png"], cwd=WORK)
    flac2 = os.path.join(WORK, "tags_existing.flac")
    ff(["-i", "sine_44100_24_st.wav", "-i", "cover.png", "-map", "0:a", "-map", "1:v",
        "-c:a", "flac", "-c:v", "copy", "-disposition:v", "attached_pic",
        "-metadata", "REPLAYGAIN_ALBUM_GAIN=-3.00 dB",
        "-metadata", "REPLAYGAIN_ALBUM_PEAK=0.7500",
        "-metadata", "title=RG Test Album", "tags_existing.flac"], cwd=WORK)
    fb, _ = md5_pcm(flac2)
    rc, out, err = java(["readtags", "tags_existing.flac"])
    check("TAG REPLAYGAIN_ALBUM_GAIN=-3.00 dB" in out and "HASRG false" in out,
          "existing ALBUM tags readable, no TRACK tags yet", out + err)
    rc, out, err = java(["writetags", "tags_existing.flac", "REPLAYGAIN_TRACK_GAIN=2.25 dB",
                         "REPLAYGAIN_TRACK_PEAK=0.9876"])
    check(rc == 0, "writeFlac on file with picture block", out + err)
    fa, _ = md5_pcm(flac2)
    check(fb == fa, "FLAC+picture decoded PCM md5 unchanged")
    tags = probe_tags(flac2)
    check(tags.get("REPLAYGAIN_TRACK_GAIN") == "2.25 dB", "merged TRACK_GAIN", str(tags))
    check(tags.get("REPLAYGAIN_ALBUM_GAIN") == "-3.00 dB", "existing ALBUM_GAIN preserved")
    check(tags.get("REPLAYGAIN_ALBUM_PEAK") == "0.7500", "existing ALBUM_PEAK preserved")
    check(tags.get("TITLE") == "RG Test Album", "unrelated Vorbis field preserved", str(tags))
    rc, out, err = run([FP, "-v", "error", "-show_streams", "-show_entries",
                        "stream=codec_name,codec_type", "-of", "json", flac2])
    streams = json.loads(out).get("streams", [])
    check(any(st.get("codec_type") == "video" for st in streams),
          "cover art (PICTURE) metadata block still present")
    rc, out, err = java(["readtags", "tags_existing.flac"])
    check("TAG REPLAYGAIN_TRACK_GAIN=2.25 dB" in out and "HASRG true" in out,
          "readFlac after merge", out + err)

    # --- chunk structure edge cases --------------------------------------
    odd = os.path.join(WORK, "odd_chunk.wav")
    insert_odd_chunk(base, odd)
    rc, out, err = java(["lufs", "odd_chunk.wav"])
    check("RESULT" in out, "odd-sized chunk + pad parses", out + err)
    odd_lufs = float([t for t in out.split() if t.startswith("lufs=")][0][5:])
    rl, _ = ref_lufs("sine_48000_16_st.wav")
    check(abs(odd_lufs - rl) <= 0.1, "odd chunk loudness matches reference",
          "%.3f vs %.3f" % (odd_lufs, rl))
    ob, _ = md5_pcm(odd)
    rc, out, err = java(["writetags", "odd_chunk.wav", "REPLAYGAIN_TRACK_GAIN=-1.00 dB"])
    oa, _ = md5_pcm(odd)
    check(ob == oa, "odd chunk WAV audio md5 unchanged after tagging")
    riff, size, chunks, _ = wav_chunks(odd)
    check(riff == size - 8 and any(c[0] == b"junk" for c in chunks),
          "odd chunk preserved with consistent RIFF size")

    lying = os.path.join(WORK, "lying_riff.wav")
    shutil.copy(base, lying)
    with open(lying, "r+b") as f:
        f.seek(4)
        f.write(struct.pack("<I", 0))  # RIFF size lies: 0
    rc, out, err = java(["lufs", "lying_riff.wav"])
    check("RESULT" in out, "untrusted RIFF size tolerated", out + err)
    lv = float([t for t in out.split() if t.startswith("lufs=")][0][5:])
    check(abs(lv - rl) <= 0.1, "lying RIFF size still measures correctly", "%.3f" % lv)

    # truncated data chunk is still readable, but must not be rewritten
    trunc = os.path.join(WORK, "truncated.wav")
    with open(base, "rb") as f:
        raw = f.read()
    with open(trunc, "wb") as f:
        f.write(raw[:len(raw) // 2])
    rc, out, err = java(["lufs", "truncated.wav"])
    check("RESULT" in out, "truncated WAV still analyzable", out + err)
    rc, out, err = java(["writetags", "truncated.wav", "REPLAYGAIN_TRACK_GAIN=-1.00 dB"])
    check("truncated" in (out + err).lower(), "truncated WAV refused for writing", out + err)

    # --- RgStore ----------------------------------------------------------
    print("\n== RgStore")
    rc, out, err = java(["storetest", TAGDIR])
    check(rc == 0 and "STORETEST ok" in out, "RgStore save/load/fresh/corrupt", out + err)

    # --- progress / cancel ------------------------------------------------
    print("\n== progress & cancel")
    rc, out, err = java(["lufs", "sine_48000_16_st.wav", "--cancel"])
    d = {}
    for token in out.split():
        k, _, v = token.partition("=")
        d[k] = v
    check(rc == 0 and d.get("cancelled") == "true" and d.get("lufs") == "NaN",
          "cancel leaves lufs NaN and flags cancelled", out + err)


def id3_tag(frames):
    body = b"".join(frames)
    n = len(body)
    return (b"ID3\x03\x00\x00"
            + bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F]) + body)


def id3_frame(fid, payload):
    if isinstance(fid, str):
        fid = fid.encode("latin-1")
    return fid + struct.pack(">I", len(payload)) + b"\x00\x00" + payload


def id3_text(fid, text):
    return id3_frame(fid, b"\x00" + text.encode("latin-1"))


def id3_txxx(desc, value):
    return id3_frame(b"TXXX", b"\x00" + desc.encode("latin-1") + b"\x00"
                     + value.encode("latin-1"))


def insert_id3_chunk(path, tag):
    """Insert an 'id3 ' chunk before the data chunk and fix the RIFF size."""
    with open(path, "rb") as f:
        data = bytearray(f.read())
    pos = 12
    insert_at = None
    while pos + 8 <= len(data):
        cid = bytes(data[pos:pos + 4])
        size = struct.unpack("<I", bytes(data[pos + 4:pos + 8]))[0]
        if cid == b"data":
            insert_at = pos
            break
        pos += 8 + size + (size & 1)
    if insert_at is None:
        raise RuntimeError("no data chunk in " + path)
    chunk = b"id3 " + struct.pack("<I", len(tag)) + tag + (b"\x00" if len(tag) & 1 else b"")
    out = data[:insert_at] + chunk + data[insert_at:]
    struct.pack_into("<I", out, 4, len(out) - 8)
    with open(path, "wb") as f:
        f.write(out)


def insert_odd_chunk(src, dst):
    with open(src, "rb") as f:
        data = bytearray(f.read())
    pos = 12
    insert_at = None
    while pos + 8 <= len(data):
        cid = bytes(data[pos:pos + 4])
        size = struct.unpack("<I", bytes(data[pos + 4:pos + 8]))[0]
        if cid == b"data":
            insert_at = pos
            break
        pos += 8 + size + (size & 1)
    if insert_at is None:
        raise RuntimeError("no data chunk in " + src)
    # an unknown chunk id, not LIST: LIST needs a form type of its own
    chunk = b"junk" + struct.pack("<I", 3) + b"abc" + b"\x00"
    out = data[:insert_at] + chunk + data[insert_at:]
    struct.pack_into("<I", out, 4, len(out) - 8)
    with open(dst, "wb") as f:
        f.write(out)


# ------------------------------------------------------------------ MP4 / M4A

MP4DIR = os.path.join(WORK, "m4a")
RG4 = [("REPLAYGAIN_TRACK_GAIN", "-7.25 dB"), ("REPLAYGAIN_TRACK_PEAK", "0.8891"),
       ("REPLAYGAIN_ALBUM_GAIN", "-6.50 dB"), ("REPLAYGAIN_ALBUM_PEAK", "0.9200")]
CONTAINERS = (b"moov", b"trak", b"mdia", b"minf", b"stbl", b"udta", b"ilst")


def mp4_boxes(data, start=0, end=None):
    """Direct child boxes of [start, end) as dicts, using the 32/64 bit size fields."""
    if end is None:
        end = len(data)
    out = []
    pos = start
    while pos + 8 <= end:
        size = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = bytes(data[pos + 4:pos + 8])
        header = 8
        if size == 1:
            if pos + 16 > end:
                break
            size = struct.unpack(">Q", data[pos + 8:pos + 16])[0]
            header = 16
        elif size == 0:
            size = end - pos
        if size < header or pos + size > end:
            break
        out.append({"type": typ, "start": pos, "size": size, "header": header,
                    "body": pos + header, "end": pos + size})
        pos += size
    return out


def mp4_child(boxes, typ):
    for b in boxes:
        if b["type"] == typ:
            return b
    return None


def mp4_qpath(data, names):
    """Follow a chain of direct children (meta is a full box: children start 4 bytes later)."""
    boxes = mp4_boxes(data)
    box = None
    for name in names:
        box = mp4_child(boxes, name)
        if box is None:
            return None
        boxes = mp4_boxes(data, box["body"] + (4 if box["type"] == b"meta" else 0), box["end"])
    return box


def mp4_ilst_items(data):
    box = mp4_qpath(data, [b"moov", b"udta", b"meta", b"ilst"])
    items = []
    if box is None:
        return items
    for it in mp4_boxes(data, box["body"], box["end"]):
        entry = {"type": it["type"], "mean": None, "name": None,
                 "data_type": None, "locale": None, "value": None,
                 "start": it["start"], "size": it["size"]}
        for ch in mp4_boxes(data, it["body"], it["end"]):
            payload = data[ch["body"]:ch["end"]]
            if ch["type"] == b"mean":
                entry["mean"] = payload[4:].decode("utf-8", "replace")
            elif ch["type"] == b"name":
                entry["name"] = payload[4:].decode("utf-8", "replace")
            elif ch["type"] == b"data" and len(payload) >= 8:
                entry["data_type"] = struct.unpack(">I", payload[0:4])[0]
                entry["locale"] = struct.unpack(">I", payload[4:8])[0]
                entry["value"] = payload[8:].decode("utf-8", "replace")
        items.append(entry)
    return items


def mp4_freeform(data):
    return {i["name"]: i for i in mp4_ilst_items(data) if i["type"] == b"----"}


def mp4_covr(data):
    for i in mp4_ilst_items(data):
        if i["type"] == b"covr":
            box = mp4_boxes(data, i["start"] + 8, i["start"] + i["size"])
            for ch in box:
                if ch["type"] == b"data":
                    return bytes(data[ch["body"] + 8:ch["end"]])
    return None


def mp4_stco_blob(data):
    """Every stco/co64 payload, in file order: byte identical means untouched offsets."""
    out = []

    def walk(start, end):
        for b in mp4_boxes(data, start, end):
            if b["type"] in (b"stco", b"co64"):
                out.append(bytes(data[b["start"]:b["end"]]))
            elif b["type"] in CONTAINERS:
                walk(b["body"], b["end"])
            elif b["type"] == b"meta":
                walk(b["body"] + 4, b["end"])

    walk(0, len(data))
    return b"".join(out)


def patch_stco(buf, delta):
    """Add delta to every chunk offset (test fixture builder only; production code never does)."""
    count = [0]

    def walk(start, end):
        for b in mp4_boxes(buf, start, end):
            if b["type"] == b"stco":
                n = struct.unpack(">I", buf[b["body"] + 4:b["body"] + 8])[0]
                for i in range(n):
                    o = b["body"] + 8 + 4 * i
                    v = struct.unpack(">I", buf[o:o + 4])[0]
                    struct.pack_into(">I", buf, o, v + delta)
                    count[0] += 1
            elif b["type"] == b"co64":
                n = struct.unpack(">I", buf[b["body"] + 4:b["body"] + 8])[0]
                for i in range(n):
                    o = b["body"] + 8 + 8 * i
                    v = struct.unpack(">Q", buf[o:o + 8])[0]
                    struct.pack_into(">Q", buf, o, v + delta)
                    count[0] += 1
            elif b["type"] in CONTAINERS:
                walk(b["body"], b["end"])
            elif b["type"] == b"meta":
                walk(b["body"] + 4, b["end"])

    walk(0, len(buf))
    return count[0]


def probe_streams(path):
    rc, out, err = run([FP, "-v", "error", "-show_entries",
                        "stream=codec_name,codec_type,channels,sample_rate,bits_per_raw_sample",
                        "-of", "json", path])
    if rc != 0:
        raise RuntimeError("ffprobe failed on " + path + ": " + err[-300:])
    streams = json.loads(out).get("streams", [])
    # ffmpeg promotes our free form tags to "Replay Gain" stream side data, so side_data_list
    # legitimately appears after a write; it is not part of the stream list identity.
    for s in streams:
        s.pop("side_data_list", None)
    return streams


def make_m4a(name, rate, bits, ch, faststart=False, codec="alac", seconds=4.0, tone=440):
    path = os.path.join(MP4DIR, name)
    if os.path.isfile(path) and os.path.getsize(path) > 0:
        return path
    args = ["-f", "lavfi", "-i",
            "sine=frequency=%d:sample_rate=%d:duration=%.2f" % (tone, rate, seconds)]
    if ch > 2:
        args += ["-ac", str(ch), "-af", "pan=5.1|FL=c0|FR=c0|FC=c0|LFE=c0|BL=c0|BR=c0"]
    else:
        args += ["-ac", str(ch)]
    if codec == "alac":
        args += ["-c:a", "alac", "-sample_fmt", "s16p" if bits == 16 else "s32p"]
    else:
        args += ["-c:a", "aac", "-b:a", "160k"]
    if faststart:
        args += ["-movflags", "+faststart"]
    args += [name]
    ff(args, cwd=MP4DIR)
    return path


def relocate_moov_to_front(src, dst):
    """Fixture: move moov in front of mdat and fix stco, i.e. hand-build a faststart layout."""
    data = open(src, "rb").read()
    top = mp4_boxes(data)
    moov = mp4_child(top, b"moov")
    mdats = [b for b in top if b["type"] == b"mdat"]
    new = bytearray()
    for b in top:
        if b["type"] not in (b"moov", b"mdat"):
            new += data[b["start"]:b["end"]]
    moov_off = len(new)
    new += data[moov["start"]:moov["end"]]
    mdat_off = len(new)
    sub = bytearray(new[moov_off:moov_off + moov["size"]])
    n = patch_stco(sub, mdat_off - mdats[0]["start"])
    new[moov_off:moov_off + moov["size"]] = sub
    for b in mdats:
        new += data[b["start"]:b["end"]]
    open(dst, "wb").write(new)
    return n


def m4a_tests():
    print("\n== MP4 / M4A ReplayGain tags (foobar2000 free form atoms)")
    if not os.path.isdir(MP4DIR):
        os.makedirs(MP4DIR)
    variants = [
        ("m4a16_st_moovend", 16, 2, False, "alac"),
        ("m4a24_st_moovend", 24, 2, False, "alac"),
        ("m4a24_st_faststart", 24, 2, True, "alac"),
        ("m4a16_51_faststart", 16, 6, True, "alac"),
        ("m4a24_51_moovend", 24, 6, False, "alac"),
        ("m4a16_st_aac", 16, 2, False, "aac"),
    ]
    for name, bits, ch, faststart, codec in variants:
        fixture = make_m4a(name + ".src.m4a", 48000, bits, ch, faststart, codec)
        test = os.path.join(MP4DIR, name + ".m4a")
        shutil.copy(fixture, test)
        before, _ = md5_pcm(test)
        data0 = open(test, "rb").read()
        stco0 = mp4_stco_blob(data0)
        streams0 = probe_streams(test)
        rc, out, err = java(["writetags", test] + ["%s=%s" % kvp for kvp in RG4])
        check(rc == 0 and "WROTE 4" in out, "writeMp4 " + name, out + err)
        after, _ = md5_pcm(test)
        check(before == after, "M4A decoded PCM md5 unchanged " + name, before + " != " + after)
        data1 = open(test, "rb").read()
        check(mp4_stco_blob(data1) == stco0,
              "sample table (stco/co64) byte identical " + name)
        check(len([b for b in mp4_boxes(data1) if b["type"] == b"moov"]) == 1,
              "exactly one moov box " + name)
        ff_items = mp4_freeform(data1)
        ok = True
        detail = []
        for key, value in RG4:
            item = ff_items.get(key)
            if item is None or item["mean"] != "com.apple.iTunes" or item["data_type"] != 1 \
                    or item["locale"] != 0 or item["value"] != value:
                ok = False
                detail.append(str(item))
        check(ok, "ilst '----' mean/name/data(type 1, locale 0) " + name, " ".join(detail)[:400])
        tags = probe_tags(test)
        check(all(tags.get(k) == v for k, v in RG4), "ffprobe sees the four tags " + name,
              str(tags))
        check(probe_streams(test) == streams0, "ffprobe stream list unchanged " + name,
              str(probe_streams(test)))
        rc, out, err = java(["readtags", test])
        check("HASRG true" in out and "TAG REPLAYGAIN_ALBUM_GAIN=-6.50 dB" in out,
              "readMp4 returns the tags " + name, out + err)
        # shorter value then longer value: both rewrite paths must stay lossless
        rc, out, err = java(["writetags", test, "REPLAYGAIN_TRACK_GAIN=-1.0 d"])
        check(rc == 0, "short value rewrite " + name, out + err)
        data2 = open(test, "rb").read()
        check(mp4_stco_blob(data2) == stco0, "short rewrite keeps stco " + name)
        rc, out, err = java(["writetags", test,
                             "REPLAYGAIN_TRACK_GAIN=-123.45 dB extra padding"])
        check(rc == 0, "long value rewrite " + name, out + err)
        data3 = open(test, "rb").read()
        check(mp4_stco_blob(data3) == stco0, "long rewrite keeps stco " + name)
        check(mp4_freeform(data3).get("REPLAYGAIN_TRACK_GAIN", {}).get("value")
              == "-123.45 dB extra padding", "ffprobe/our reader sees the new value " + name,
              str(mp4_freeform(data3).get("REPLAYGAIN_TRACK_GAIN")))
        after2, _ = md5_pcm(test)
        check(before == after2, "decoded PCM md5 unchanged after rewrites " + name)
        size2 = os.path.getsize(test)
        java(["writetags", test, "REPLAYGAIN_TRACK_GAIN=-123.45 dB extra padding"])
        check(os.path.getsize(test) == size2, "same tags are idempotent (no growth) " + name)
        # album fields the caller does not mention survive a track-only merge
        rc, out, err = java(["readtags", test])
        check("TAG REPLAYGAIN_ALBUM_GAIN=-6.50 dB" in out,
              "untouched ReplayGain fields survive a partial rewrite " + name, out + err)

    # --- faststart with existing tags: the moov must keep its exact size ----------
    base = os.path.join(MP4DIR, "m4a24_st_faststart.m4a")
    fixture = os.path.join(MP4DIR, "m4a_pad.m4a")
    moved = relocate_moov_to_front(base, fixture)
    check(moved >= 1, "fixture: moov moved in front of mdat with stco fixed", str(moved))
    data = open(fixture, "rb").read()
    top = mp4_boxes(data)
    moov = mp4_child(top, b"moov")
    mdat = mp4_child(top, b"mdat")
    check(moov["start"] < mdat["start"], "fixture really is moov-before-mdat")
    size0 = len(data)
    stco0 = mp4_stco_blob(data)
    md50, _ = md5_pcm(fixture)
    rc, out, err = java(["writetags", fixture, "REPLAYGAIN_TRACK_GAIN=-1.0 d"])
    check(rc == 0, "in place rewrite of a faststart m4a", out + err)
    data2 = open(fixture, "rb").read()
    check(len(data2) == size0, "free box padding keeps the exact file size and mdat offset",
          "%d vs %d" % (len(data2), size0))
    check(mp4_child(mp4_boxes(data2), b"mdat")["start"] == mdat["start"],
          "mdat stays at its absolute offset")
    check(mp4_stco_blob(data2) == stco0, "stco byte identical after in place padding")
    md51, _ = md5_pcm(fixture)
    check(md50 == md51, "decoded PCM md5 stable after in place padding")
    check(mp4_freeform(data2).get("REPLAYGAIN_TRACK_GAIN", {}).get("value") == "-1.0 d",
          "in place rewrite stores the new value")
    rc, out, err = java(["writetags", fixture, "REPLAYGAIN_TRACK_GAIN=-1"])
    data3 = open(fixture, "rb").read()
    check(len(data3) == size0, "handler name padding handles a 4 byte shrink, size still exact")
    check(mp4_stco_blob(data3) == stco0, "stco still byte identical after handler padding")
    check(mp4_freeform(data3).get("REPLAYGAIN_TRACK_GAIN", {}).get("value") == "-1",
          "handler padded rewrite stores the new value")

    # --- growth in front of the media data: moov moves to the end ----------------
    fresh = make_m4a("m4a24_st_faststart.m4a", 48000, 24, 2, True, "alac")
    grow = os.path.join(MP4DIR, "m4a_grow.m4a")
    shutil.copy(fresh, grow)
    gdata = open(grow, "rb").read()
    gmdat = mp4_child(mp4_boxes(gdata), b"mdat")
    gstco = mp4_stco_blob(gdata)
    gmd5, _ = md5_pcm(grow)
    rc, out, err = java(["writetags", grow] + ["%s=%s" % kvp for kvp in RG4])
    check(rc == 0, "write into a faststart m4a with no space for the tags", out + err)
    gdata2 = open(grow, "rb").read()
    check(mp4_child(mp4_boxes(gdata2), b"mdat")["start"] == gmdat["start"],
          "moov relocation keeps mdat at its absolute offset")
    check(mp4_stco_blob(gdata2) == gstco, "moov relocation keeps stco byte identical")
    check(mp4_child(mp4_boxes(gdata2), b"moov") is not None, "moov still present after relocation")
    gmd5b, _ = md5_pcm(grow)
    check(gmd5 == gmd5b, "decoded PCM md5 stable after relocation")
    check(all(probe_tags(grow).get(k) == v for k, v in RG4), "tags visible after relocation")

    # --- cover art and other items are copied, not parsed ----------------------
    png = os.path.join(MP4DIR, "cover.png")
    if not os.path.isfile(png):
        ff(["-f", "lavfi", "-i", "nullsrc=s=2600x2600,geq=random(1)*255:128:128",
            "-frames:v", "1", "-pix_fmt", "rgb24", "cover.png"], cwd=MP4DIR)
    cover = os.path.join(MP4DIR, "m4a_cover.m4a")
    ff(["-i", "m4a16_st_moovend.src.m4a", "-i", "cover.png", "-map", "0:a", "-map", "1",
        "-c:a", "copy", "-c:v", "mjpeg", "-disposition:v", "attached_pic",
        "m4a_cover.m4a"], cwd=MP4DIR)
    cdata0 = open(cover, "rb").read()
    cov0 = mp4_covr(cdata0)
    cmd5, _ = md5_pcm(cover)
    streams = probe_streams(cover)
    rc, out, err = java(["writeheap", cover] + ["%s=%s" % kvp for kvp in RG4], xmx="16m")
    check(rc == 0 and "WROTE 4" in out, "writeMp4 with a large cover art under -Xmx16m", out + err)
    cdata1 = open(cover, "rb").read()
    check(cov0 is not None and cov0 == mp4_covr(cdata1),
          "covr payload copied byte for byte (cover art untouched)")
    cover_peak = int(kv(out.strip().splitlines()[-1], "WROTE 4 ").get("heapPeak", "0"))
    check(0 < cover_peak < 16 * 1024 * 1024,
          "cover art does not enter the heap (peak %d bytes)" % cover_peak)
    check(all(probe_tags(cover).get(k) == v for k, v in RG4), "ffprobe tags with cover art")
    check(probe_streams(cover) == streams, "audio + cover streams unchanged")
    cmd5b, _ = md5_pcm(cover)
    check(cmd5 == cmd5b, "decoded PCM md5 unchanged with cover art")

    # --- an 18 MB cover must be copied as a range, never loaded ---------------
    big_jpg = os.path.join(MP4DIR, "bigcover.jpg")
    if not os.path.isfile(big_jpg):
        ff(["-f", "lavfi", "-i", "nullsrc=s=5000x5000,geq=random(1)*255:128:128",
            "-frames:v", "1", "-q:v", "2", "bigcover.jpg"], cwd=MP4DIR)
    big_cover = os.path.join(MP4DIR, "m4a_bigcover.m4a")
    ff(["-i", "m4a16_st_moovend.src.m4a", "-i", "bigcover.jpg", "-map", "0:a", "-map", "1",
        "-c:a", "copy", "-c:v", "copy", "-disposition:v", "attached_pic",
        "m4a_bigcover.m4a"], cwd=MP4DIR)
    bdata0 = open(big_cover, "rb").read()
    bcov0 = mp4_covr(bdata0)
    check(bcov0 is not None and len(bcov0) > 8 * 1024 * 1024,
          "big cover fixture is larger than 8 MB (%d bytes)" % len(bcov0 or b""))
    bmd5, _ = md5_pcm(big_cover)
    rc, out, err = java(["writeheap", big_cover] + ["%s=%s" % kvp for kvp in RG4], xmx="12m")
    check(rc == 0 and "WROTE 4" in out,
          "%.1f MB cover art tagged under -Xmx12m" % (len(bcov0 or b"") / 1048576.0),
          out + err)
    bpeak = int(kv(out.strip().splitlines()[-1], "WROTE 4 ").get("heapPeak", "0"))
    check(0 < bpeak < 12 * 1024 * 1024,
          "big cover heap peak stays under the cap (%d bytes)" % bpeak)
    check(mp4_covr(open(big_cover, "rb").read()) == bcov0,
          "big covr payload copied byte for byte")
    check(md5_pcm(big_cover)[0] == bmd5, "decoded PCM md5 unchanged with an 18 MB cover")
    check(all(probe_tags(big_cover).get(k) == v for k, v in RG4), "tags visible with a big cover")
    print("  big cover: %.1f MB covr, heap peak %.1f MB (cap 12 MB)"
          % (len(bcov0) / 1048576.0, bpeak / 1048576.0))

    # --- a file without moov must be refused without touching it ---------------
    broken = os.path.join(MP4DIR, "m4a_broken.m4a")
    with open(broken, "wb") as fh:
        fh.write(struct.pack(">I", 20) + b"ftypM4A " + b"\x00" * 96)
    orig = open(broken, "rb").read()
    rc, out, err = java(["writetags", broken, "REPLAYGAIN_TRACK_GAIN=0 dB"])
    check(rc != 0 and "moov" in (out + err).lower(), "writeMp4 refuses a file without moov",
          (out + err)[-300:])
    check(open(broken, "rb").read() == orig, "original file is byte identical after a failed write")
    rc, out, err = java(["readtags", broken])
    check(rc == 0 and "HASRG false" in out, "readMp4 degrades to no tags on a broken file",
          out + err)

    # --- album grouping keys are pure and testable -----------------------------
    rc, out, err = java(["albumkey", "Pink Floyd|The Wall", "Pink Floyd|The Wall",
                         "Pink Floyd|Animals", "|The Wall", "  |  The Wall  ", "|", "|   ",
                         "Solo|"])
    keys = {}
    unknown = {}
    for line in out.splitlines():
        if not line.startswith("KEY "):
            continue
        body, _, rest = line[4:].partition(" => ")
        key, _, flag = rest.partition(" unknown=")
        keys[body] = key
        unknown[body] = flag.strip() == "true"
    check(keys.get("Pink Floyd|The Wall") is not None,
          "albumKey produces a key for a complete pair")
    check(keys.get("Pink Floyd|The Wall") != keys.get("Pink Floyd|Animals"),
          "different albums of one artist are different groups")
    check(keys.get("|The Wall") == keys.get("  |  The Wall  "),
          "empty album artist falls back to the album and is trimmed")
    check(unknown.get("|") and unknown.get("|   "),
          "missing album artist and album collapse into the unknown album group")
    check(keys.get("|The Wall") != keys.get("Solo|"),
          "album-only and artist-only keys stay distinct", str(keys))


# ------------------------------------------------------------------ album loudness

ALBUM_SETS = [
    # name, rate, bits, channels, [(seconds, amplitude, channel mask), ...]
    ("alb_st16", 48000, 16, 2, [(4.0, 0.50, 0x3), (3.3, 0.12, 0x3), (2.7, 0.04, 0x3)]),
    ("alb_mono24", 44100, 24, 1, [(3.9, 0.70, 0x4), (2.2, 0.20, 0x4)]),
    ("alb_51_24", 48000, 24, 6, [(3.5, 0.40, 0x3F), (2.5, 0.10, 0x3F), (4.1, 0.55, 0x3F)]),
]


def concat_wavs(paths, dst):
    """Byte concatenation of the data chunks: exactly the stream the analyser is asked to measure."""
    fmt = None
    data = b""
    for path in paths:
        raw = open(path, "rb").read()
        pos = 12
        while pos + 8 <= len(raw):
            cid = raw[pos:pos + 4]
            size = struct.unpack("<I", raw[pos + 4:pos + 8])[0]
            body = pos + 8
            if body + size > len(raw):
                break
            if cid == b"fmt " and fmt is None:
                fmt = raw[body:body + size]
            elif cid == b"data":
                data += raw[body:body + size]
            pos = body + size + (size & 1)
    out = b"RIFF" + struct.pack("<I", 4 + 8 + len(fmt) + 8 + len(data)) + b"WAVE"
    out += b"fmt " + struct.pack("<I", len(fmt)) + fmt
    out += b"data" + struct.pack("<I", len(data)) + data
    open(dst, "wb").write(out)


def album_tests():
    print("\n== album loudness (whole group as one continuous stream)")
    for name, rate, bits, ch, parts in ALBUM_SETS:
        paths = []
        for i, (seconds, amp, mask) in enumerate(parts):
            path = os.path.join(WORK, "%s_%d.wav" % (name, i))
            if not os.path.isfile(path):
                write_multi_wav(path, rate, bits, ch, [amp] * ch, mask,
                                seconds=seconds, freq=1000.0 * (i + 1))
            paths.append(path)
        cat = os.path.join(WORK, name + "_cat.wav")
        concat_wavs(paths, cat)
        ref = N(ref_lufs(name + "_cat.wav"))
        rc, out, err = java(["album"] + paths)
        got = parse_album(out)
        our_lufs = float(got["lufs"])
        d = our_lufs - ref
        check(abs(d) <= 0.1,
              "album LUFS vs concatenated ebur128 %s (%.2f vs %.2f)" % (name, our_lufs, ref),
              "delta %.3f LU" % d)
        ref_peak_db = N(ref_peak(name + "_cat.wav"))
        our_peak_db = float(got["peakDb"])
        check(abs(our_peak_db - ref_peak_db) < 1e-3,
              "album sample peak vs astats %s" % name,
              "%.4f vs %.4f" % (our_peak_db, ref_peak_db))
        check(int(got["files"]) == len(paths), "album analyser saw every file " + name)
        check(abs(float(got["sec"]) - sum(p[0] for p in parts)) < 1e-6,
              "album duration is the sum of the tracks (no inserted silence) " + name, got["sec"])
        # a single file through the album analyser must equal the single file meter
        _, solo, _ = java(["album", paths[0]])
        _, track, _ = java(["lufs", paths[0]])
        check(abs(float(kv(solo.splitlines()[0], "ALBUM ")["lufs"])
                  - float(kv(track.splitlines()[0], "RESULT ")["lufs"])) < 1e-6,
              "album analyser of one file equals analyzeWav " + name)
        # the simple average of per track loudness must differ: this is not an average
        per = []
        for path in paths:
            rc, out, err = java(["lufs", path])
            per.append(float(kv(out.splitlines()[0], "RESULT ")["lufs"]))
        avg = sum(per) / len(per)
        check(abs(avg - our_lufs) > 0.5,
              "album result is not the mean of the track loudness " + name,
              "mean %.2f vs album %.2f" % (avg, our_lufs))

    # mixed formats must not crash and must stay finite
    a = os.path.join(WORK, "alb_mix_48.wav")
    b = os.path.join(WORK, "alb_mix_44.wav")
    if not os.path.isfile(a):
        write_multi_wav(a, 48000, 16, 2, [0.3, 0.3], 0x3, seconds=2.5)
    if not os.path.isfile(b):
        write_multi_wav(b, 44100, 16, 2, [0.3, 0.3], 0x3, seconds=2.5)
    rc, out, err = java(["album", a, b])
    got = parse_album(out)
    check(rc == 0 and got["lufs"] != "NaN", "mixed sample rates still produce one finite result",
          out + err)

    # cancellation aborts an album run and yields no measurement
    rc, out, err = java(["album", "--cancel"] + [os.path.join(WORK, ALBUM_SETS[0][0] + "_%d.wav" % i)
                                                 for i in range(3)])
    got = parse_album(out)
    check(rc == 0 and got.get("cancelled") == "true" and got.get("lufs") == "NaN",
          "cancelling an album run yields NaN", out + err)


def memory_tests():
    print("\n== streaming / memory")
    parts = []
    for i in range(8):
        path = os.path.join(WORK, "mem_big_%d.wav" % i)
        if not os.path.isfile(path):
            ff(["-f", "lavfi", "-i",
                "sine=frequency=%d:sample_rate=48000:duration=60" % (220 + 110 * i),
                "-ac", "2", "-c:a", "pcm_s16le", "mem_big_%d.wav" % i], cwd=WORK)
        parts.append(path)
    total = sum(os.path.getsize(p) for p in parts)
    rc, out, err = java(["album"] + parts, xmx="20m")
    got = parse_album(out)
    check(rc == 0 and got["lufs"] != "NaN",
          "8 min album under -Xmx20m (%.1f MB of PCM)" % (total / 1048576.0), out + err)
    peak = int(got["heapPeak"])
    check(peak < 20 * 1024 * 1024, "album heap peak stays small (%d bytes)" % peak)
    print("  album memory: %.1f MB of PCM, heap peak %.1f MB, %s s"
          % (total / 1048576.0, peak / 1048576.0, got.get("sec")))
    print("  m4a memory:  cover art write heap peak (see the cover art check above)")


def coefficients():
    print("\n== K-weighting re-derivation vs BS.1770-4 48 kHz table")
    rc, out, err = java(["coeff"])
    want = {"48000": (1.53512485958697, -2.69169618940638, 1.19839281085285,
                      -1.69065929318241, 0.73248077421585)}
    got = {}
    for line in out.splitlines():
        if not line.startswith("RATE "):
            continue
        parts = line.split()
        rate = parts[1]
        vals = []
        for seg in (parts[2].split("=")[1], parts[3].split("=")[1]):
            vals.extend(float(x) for x in seg.split(","))
        got[rate] = vals
    for rate, exp in want.items():
        v = got.get(rate)
        ok = v is not None and max(abs(x - y) for x, y in zip(v, exp)) < 1e-6
        check(ok, "48 kHz coefficients equal the published table", str(v))
    for rate in ("44100", "88200", "96000", "192000"):
        v = got.get(rate)
        check(v is not None and all(abs(x) < 4 for x in v), "coefficients sane at " + rate,
              str(v))


def main():
    build()
    build_replaygain()
    gen()
    coefficients()
    rows = loudness_table()
    tag_tests(rows)
    m4a_tests()
    album_tests()
    memory_tests()
    print("\n== summary")
    print("checks: %d, failures: %d" % (CHECKS[0], len(FAILS)))
    for f in FAILS:
        print("  FAILED: " + f)
    if FAILS:
        sys.exit(1)
    print("ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
