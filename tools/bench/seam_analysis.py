#!/usr/bin/env python3
"""Seam quality analysis for segmented VST rendering.

The renderer is run on several slices of one track (see Segment.cut/join); each slice
is rendered with a pre-roll that is dropped afterwards.  A seam is the sample index of
the first sample of the next slice, so the discontinuity (if any) sits between
seam-1 and seam.  This tool answers one question per seam: did the join introduce
something audible, and if so, is it a click or a change of the ambient/reverb tail?

Usage
  python tools/seam_analysis.py --ref whole.wav --test joined.wav \
         --seams 960000,1920000 --preroll 1.35 [--json out.json] [--quiet]

  python tools/seam_analysis.py --selftest tools/bench60.wav [--keep DIR]
         # generates clean/click/ambient variants and analyses all three

Only reads WAV (8/16/24/32-bit int, 32-bit float, WAVE_FORMAT_EXTENSIBLE),
needs numpy + scipy, writes nothing except the optional --json file.

Metrics (all relative to a local baseline, so the verdict does not depend on the
programme material's own dynamics):
  1. step      |x[n]-x[n-1]| at the seam vs the p99.9 of that difference in +-2 s,
               plus the same numbers for the reference render at the same index.
  2. envelope  10 ms RMS frames; largest frame-to-frame change within +-100 ms vs
               the mean/std of that change over +-3 s -> z-score.
  3. flux      STFT(1024, hop 512, hann); seam frame flux vs p99.5 over +-5 s.
  4. diff      test-reference inside seam +- preroll: RMS/peak dBFS, how the energy
               is distributed in time (impulse vs spread), and whether its spectrum
               follows the reference (reverb-tail difference) or is flat
               (quantisation / block alignment).

Verdicts (thresholds live at the top of the file and are echoed into the JSON):
  click                diff peak >= -70 dBFS and either >=50 % of the diff energy sits
                       within +-1 ms of the join, or the step is >= 4x the local p99.9
                       and >= 4x the reference step (a step the reference does not have).
  ambient-shift        no such step, but the diff is louder than -90 dBFS RMS and is
                       spread out (>= 20 ms p90 spread or < 50 % inside +-20 ms); the
                       spectrum usually follows the reference -> the reverb tail that
                       each slice lost/restarted differs.
  no-audible-artifact  diff <= -90 dBFS RMS with a normal step, envelope and flux.
  inconclusive         neither pattern is clear; the numbers are in the JSON.
"""

import argparse
import json
import math
import os
import shutil
import struct
import sys
import tempfile
import time

import numpy as np

try:
    from scipy.signal import welch
except Exception:                                    # pragma: no cover - scipy is optional
    welch = None

# --------------------------------------------------------------------------- tuning
STEP_WINDOW_S = 2.0        # baseline window for the sample-step percentile
STEP_PERCENTILE = 99.9
ENV_HOP_S = 0.01           # short-time RMS frame hop (10 ms)
ENV_LOCAL_S = 0.100        # "at the seam" neighbourhood for the envelope
ENV_BASE_S = 3.0           # baseline window for the envelope
FLUX_N_FFT = 1024
FLUX_HOP = 512
FLUX_WINDOW_S = 5.0        # baseline window for spectral flux
FLUX_PERCENTILE = 99.5
DIFF_BAND_HZ = (20.0, 20000.0)

# Verdict thresholds.  A click needs a step that is both unusual locally *and*
# absent from the reference; ambient shift needs a real difference signal that is
# not concentrated at the join.
CLICK_STEP_RATIO = 4.0     # vs local p99.9
CLICK_STEP_VS_REF = 4.0    # vs the reference's step at the same index
CLICK_PEAK_DBFS = -70.0    # a seam-only transient quieter than this cannot be audible
CLICK_IMPULSE_CONC = 0.5   # fraction of the diff energy allowed inside +-1 ms
AMBIENT_DIFF_DBFS = -90.0  # below this the difference is inaudible on its own
AMBIENT_MAX_STEP_RATIO = 2.0
AMBIENT_MAX_ENV_Z = 6.0
QUIET_DIFF_DBFS = -100.0
SILENCE = -200.0


# ----------------------------------------------------------------------------- io
def read_wav(path):
    """Reads a RIFF/WAVE file into float64 (frames, channels) plus a description."""
    with open(path, "rb") as fh:
        blob = fh.read()
    if len(blob) < 12 or blob[0:4] != b"RIFF" or blob[8:12] != b"WAVE":
        raise ValueError("%s: not a RIFF/WAVE file" % path)
    pos = 12
    fmt = None
    data = None
    while pos + 8 <= len(blob):
        cid = blob[pos:pos + 4]
        size = struct.unpack("<I", blob[pos + 4:pos + 8])[0]
        body = blob[pos + 8:pos + 8 + size]
        if cid == b"fmt ":
            fmt = body
        elif cid == b"data":
            data = body
            break
        pos += 8 + size + (size & 1)
    if fmt is None or data is None:
        raise ValueError("%s: missing fmt/data chunk" % path)
    if len(fmt) < 16:
        raise ValueError("%s: short fmt chunk" % path)
    tag, channels, rate, byte_rate, block_align, bits = struct.unpack("<HHIIHH", fmt[:16])
    if tag == 0xFFFE:                               # WAVE_FORMAT_EXTENSIBLE
        if len(fmt) < 40:
            raise ValueError("%s: short extensible fmt chunk" % path)
        tag = struct.unpack("<H", fmt[24:26])[0]
    if channels < 1:
        raise ValueError("%s: %d channels" % (path, channels))
    if block_align != channels * bits // 8:
        block_align = channels * bits // 8
    usable = len(data) - (len(data) % block_align)
    raw = data[:usable]

    if tag == 3 and bits == 32:
        x = np.frombuffer(raw, dtype="<f4").astype(np.float64)
        kind = "float32"
    elif tag == 1 and bits == 8:
        x = (np.frombuffer(raw, dtype=np.uint8).astype(np.float64) - 128.0) / 128.0
        kind = "pcm8"
    elif tag == 1 and bits == 16:
        x = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
        kind = "pcm16"
    elif tag == 1 and bits == 24:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        v = b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)
        v = np.where(v & 0x800000, v - 0x1000000, v)
        x = v.astype(np.float64) / 8388608.0
        kind = "pcm24"
    elif tag == 1 and bits == 32:
        x = np.frombuffer(raw, dtype="<i4").astype(np.float64) / 2147483648.0
        kind = "pcm32"
    else:
        raise ValueError("%s: unsupported WAV tag=%d bits=%d" % (path, tag, bits))

    x = x.reshape(-1, channels)
    info = {"path": os.path.abspath(path), "format": kind, "bits": bits, "tag": tag,
            "sample_rate": rate, "channels": channels, "frames": int(x.shape[0]),
            "byte_rate": byte_rate, "block_align": block_align}
    return x, rate, info


def write_wav24(path, x, rate):
    """24-bit writer, only used by --selftest."""
    x = np.clip(x, -1.0, 1.0)
    v = np.rint(x * 8388607.0).astype(np.int32)
    flat = v.reshape(-1)
    b = np.empty((flat.size, 3), dtype=np.uint8)
    b[:, 0] = flat & 0xFF
    b[:, 1] = (flat >> 8) & 0xFF
    b[:, 2] = (flat >> 16) & 0xFF
    payload = b.tobytes()
    channels = x.shape[1]
    block = channels * 3
    header = (b"RIFF" + struct.pack("<I", 36 + len(payload)) + b"WAVE"
              + b"fmt " + struct.pack("<IHHIIHH", 16, 1, channels, rate,
                                      rate * block, block, 24)
              + b"data" + struct.pack("<I", len(payload)))
    with open(path, "wb") as fh:
        fh.write(header)
        fh.write(payload)


# ------------------------------------------------------------------------ metrics
def db(value, floor=SILENCE):
    return floor if value is None or value <= 0 else 20.0 * math.log10(value)


def peaks(x):
    """Per-sample maximum absolute difference across channels, as a 1-D array."""
    d = np.abs(np.diff(x, axis=0))
    return d.max(axis=1) if d.size else np.zeros(0)


def stft_mag(x, n_fft, hop):
    if x.shape[0] < n_fft:
        x = np.pad(x, ((0, n_fft - x.shape[0]), (0, 0)))
    frames = 1 + (x.shape[0] - n_fft) // hop
    idx = (np.arange(n_fft)[None, :] + hop * np.arange(frames)[:, None])
    win = np.hanning(n_fft)[None, :]
    out = None
    for c in range(x.shape[1]):
        mag = np.abs(np.fft.rfft(x[idx, c] * win, axis=1))
        out = mag if out is None else out + mag
    return out / x.shape[1]


def frame_rms(x, hop):
    frames = x.shape[0] // hop
    if frames < 1:
        return np.zeros(0), np.zeros(0, dtype=np.int64)
    cut = x[:frames * hop].reshape(frames, hop, x.shape[1])
    env = np.sqrt((cut ** 2).mean(axis=1))
    starts = np.arange(frames) * hop
    return env.max(axis=1), starts


def safe_z(value, mean, std):
    if std is None or std <= 1e-12:
        return 0.0 if abs(value - (mean or 0.0)) <= 1e-12 else float("inf")
    return (value - mean) / std


def psd(x, rate):
    """Welch PSD, falls back to a plain periodogram when scipy is unavailable."""
    nper = min(2048, max(256, x.size // 4))
    if welch is not None and x.size >= nper:
        f, p = welch(x, fs=rate, nperseg=nper, noverlap=nper // 2, detrend="constant")
        return f, p
    if x.size < 8:
        return np.zeros(1), np.zeros(1)
    nper = min(2048, x.size)
    win = np.hanning(nper)
    p = np.abs(np.fft.rfft(x[:nper] * win)) ** 2 / max(1.0, (win ** 2).sum())
    return np.fft.rfftfreq(nper, 1.0 / rate), p


def diff_analysis(x, ref, seam, rate, preroll_s):
    half = max(1, int(round(preroll_s * rate)))
    lo = max(0, seam - half)
    hi = min(x.shape[0], seam + half)
    d = x[lo:hi] - ref[lo:hi]
    out = {"window_frames": [int(lo), int(hi)],
           "window_s": [round(lo / rate, 6), round(hi / rate, 6)],
           "window_ms": round((hi - lo) / rate * 1000.0, 1)}
    if d.size == 0:
        out.update({"empty": True, "rms_dbfs": SILENCE, "peak_dbfs": SILENCE})
        return out
    ch_rms = np.sqrt((d ** 2).mean(axis=0))
    ch_peak = np.abs(d).max(axis=0)
    worst = int(np.argmax(ch_rms))
    dm = d[:, worst]
    total = float((dm ** 2).sum())
    out.update({
        "worst_channel": worst,
        "rms_dbfs": round(db(math.sqrt(total / dm.size)), 2),
        "peak_dbfs": round(db(float(np.abs(dm).max())), 2),
        "channels": [{"rms_dbfs": round(db(math.sqrt((d[:, c] ** 2).mean())), 2),
                      "peak_dbfs": round(db(float(np.abs(d[:, c]).max())), 2)}
                     for c in range(d.shape[1])],
    })
    out["crest_db"] = round(db(float(np.abs(dm).max())) - out["rms_dbfs"], 2)

    # Energy concentration relative to the seam: cumulative energy of |d|^2.
    if total > 0:
        e = dm ** 2
        cum = np.cumsum(e)
        def conc(ms):
            k = max(1, int(round(ms / 1000.0 * rate)))
            a = max(0, seam - lo - k)
            b = min(e.size, seam - lo + k)
            return round(float(cum[b - 1] - (cum[a - 1] if a > 0 else 0.0)) / total, 6) if b > a else 0.0
        offsets = (np.arange(e.size) - (seam - lo)) / rate * 1000.0
        order = np.argsort(np.abs(offsets))
        cums = np.cumsum(e[order]) / total
        def spread(p):
            hit = np.searchsorted(cums, p)
            return round(float(abs(offsets[order[min(hit, e.size - 1)]])), 2)
        # 5 ms energy profile: how the difference is distributed in time
        hs = max(1, int(round(0.005 * rate)))
        nb = max(1, e.size // hs)
        prof = np.array([e[i * hs:(i + 1) * hs].sum() for i in range(nb)])
        top = np.argsort(prof)[::-1][:3]
        out.update({
            "energy": {"total": total,
                       "conc_1ms": conc(1.0), "conc_5ms": conc(5.0),
                       "conc_20ms": conc(20.0), "conc_100ms": conc(100.0),
                       "spread_p50_ms": spread(0.5), "spread_p90_ms": spread(0.9),
                       "spread_p99_ms": spread(0.99),
                       "top_bins_ms": [{"offset_ms": round((int(i) * hs + hs / 2.0
                                                           - (seam - lo)) / rate * 1000.0, 2),
                                        "frac": round(float(prof[i] / total), 4)}
                                       for i in top]},
        })
    else:
        out["energy"] = {"total": 0.0}

    # Spectral shape of the difference vs the reference in the same window.
    if total > 0 and dm.size >= 256:
        f, pd = psd(dm, rate)
        _, pr = psd(ref[lo:hi, worst], rate)
        k0 = int(np.searchsorted(f, DIFF_BAND_HZ[0]))
        k1 = int(np.searchsorted(f, min(DIFF_BAND_HZ[1], f[-1])))
        k1 = max(k0 + 2, min(k1, f.size))
        pd = pd[k0:k1] + 1e-30
        prb = pr[k0:k1] + 1e-30
        corr = float(np.corrcoef(10 * np.log10(pd), 10 * np.log10(prb))[0, 1])
        flat = float(np.exp(np.mean(np.log(pd))) / np.mean(pd))
        out["spectrum"] = {"band_hz": [float(f[k0]), float(f[k1 - 1])],
                           "corr_with_reference": None if not np.isfinite(corr) else round(corr, 4),
                           "flatness_db": round(db(flat), 2)}
    else:
        out["spectrum"] = {"corr_with_reference": None, "flatness_db": None}

    # Shape guess, using only the numbers above.
    e = out.get("energy", {})
    conc1 = e.get("conc_1ms", 0.0)
    p90 = e.get("spread_p90_ms")
    crest = out.get("crest_db")
    if conc1 >= 0.05 or (crest is not None and crest >= 12.0 and p90 is not None and p90 <= 5.0):
        shape = "impulse"
    elif out["spectrum"].get("corr_with_reference") is not None \
            and out["spectrum"]["corr_with_reference"] >= 0.5:
        shape = "colored-like-reference"
    elif out["spectrum"].get("flatness_db") is not None \
            and out["spectrum"]["flatness_db"] >= -6.0:
        shape = "broadband-flat"
    else:
        shape = "mixed"
    out["shape"] = shape
    return out


def analyse_seam(x, ref, seam, rate, preroll_s):
    n = min(x.shape[0], ref.shape[0])
    s = int(seam)
    if not 1 <= s < n:
        raise ValueError("seam %d outside 1..%d" % (seam, n - 1))

    res = {"seam_index": s, "seam_time_s": round(s / rate, 6)}

    # --- 1. sample step -----------------------------------------------------
    dwin = int(STEP_WINDOW_S * rate)
    lo = max(1, s - dwin)
    hi = min(n, s + dwin)
    res["clamped"] = lo != s - dwin or hi != s + dwin
    dx = peaks(x[:n])
    dr = peaks(ref[:n])
    step_test = float(dx[s - 1])
    step_ref = float(dr[s - 1])
    base_test = float(np.percentile(dx[lo - 1:hi - 1], STEP_PERCENTILE))
    base_ref = float(np.percentile(dr[lo - 1:hi - 1], STEP_PERCENTILE))
    res["step"] = {
        "test_abs": step_test, "reference_abs": step_ref,
        "test_dbfs": round(db(step_test), 2), "reference_dbfs": round(db(step_ref), 2),
        "baseline_p999_test": base_test, "baseline_p999_reference": base_ref,
        "baseline_window_s": [round(lo / rate, 3), round(hi / rate, 3)],
        "baseline_samples": int(hi - lo),
        "ratio_vs_baseline": round(step_test / base_test, 3) if base_test > 0 else None,
        "ratio_vs_reference": round(step_test / step_ref, 3) if step_ref > 0 else None,
        "reference_ratio_vs_baseline": round(step_ref / base_ref, 3) if base_ref > 0 else None,
    }

    # --- 2. envelope continuity --------------------------------------------
    hop = max(1, int(round(ENV_HOP_S * rate)))
    env, starts = frame_rms(x[:n], hop)
    if env.size >= 4:
        denv = np.abs(np.diff(env))
        f = int(s // hop)
        local = max(1, int(round(ENV_LOCAL_S / ENV_HOP_S)))
        base = max(local + 1, int(round(ENV_BASE_S / ENV_HOP_S)))
        a, b = max(1, f - local), min(denv.size, f + local + 1)
        lo2, hi2 = max(1, f - base), min(denv.size, f + base + 1)
        dist = np.concatenate([denv[lo2:a], denv[b:hi2]]) if hi2 > lo2 else np.zeros(0)
        jump = float(denv[a:b].max()) if b > a else 0.0
        mean = float(dist.mean()) if dist.size else 0.0
        std = float(dist.std()) if dist.size else 0.0
        long_env = float(env[lo2:hi2].mean()) if hi2 > lo2 else 0.0
        env_db = 20 * np.log10(np.maximum(env[lo2:hi2], 1e-12)) if hi2 > lo2 else np.zeros(1)
        db_jump = float(np.abs(np.diff(env_db)).max()) if env_db.size > 1 else 0.0
        res["envelope"] = {
            "hop_ms": round(hop / rate * 1000.0, 3),
            "frame": f,
            "max_abs_delta": jump,
            "max_delta_db": round(db_jump, 2),
            "baseline_mean_abs": mean, "baseline_std_abs": std,
            "baseline_frames": int(dist.size),
            "z_score": round(safe_z(jump, mean, std), 2),
            "rel_jump": round(jump / long_env, 5) if long_env > 0 else None,
        }
    else:
        res["envelope"] = {"hop_ms": round(hop / rate * 1000.0, 3), "z_score": 0.0}

    # --- 3. spectral flux ---------------------------------------------------
    mag = stft_mag(x[:n], FLUX_N_FFT, FLUX_HOP)
    if mag.shape[0] >= 3:
        flux = np.zeros(mag.shape[0])
        flux[1:] = np.maximum(0.0, mag[1:] - mag[:-1]).sum(axis=1)
        fs = int(s // FLUX_HOP)
        fs = min(max(1, fs), flux.size - 1)
        fwin = max(4, int(round(FLUX_WINDOW_S * rate / FLUX_HOP)))  # frames in 5 s
        a, b = max(1, fs - fwin), min(flux.size, fs + fwin + 1)
        dist = np.concatenate([flux[a:max(a, fs - 1)], flux[min(flux.size, fs + 2):b]])
        p995 = float(np.percentile(dist, FLUX_PERCENTILE)) if dist.size else 0.0
        res["flux"] = {
            "frame": fs, "n_fft": FLUX_N_FFT, "hop": FLUX_HOP,
            "value": round(float(flux[fs]), 6),
            "baseline_p995": round(p995, 6),
            "baseline_frames": int(dist.size),
            "ratio": round(float(flux[fs]) / p995, 3) if p995 > 0 else None,
        }
    else:
        res["flux"] = None

    # --- 4. difference signal ----------------------------------------------
    dres = diff_analysis(x, ref, s, rate, preroll_s)
    res["diff"] = dres

    # --- 5. verdict ---------------------------------------------------------
    reasons = []
    step = res["step"]
    sr = step["ratio_vs_baseline"] or 0.0
    srr = step["ratio_vs_reference"]
    env_z = res["envelope"].get("z_score") or 0.0
    flux_ratio = (res["flux"] or {}).get("ratio") or 0.0
    peak_db = dres.get("peak_dbfs", SILENCE)
    rms_db = dres.get("rms_dbfs", SILENCE)
    e = dres.get("energy", {})
    conc1 = e.get("conc_1ms", 0.0)
    conc20 = e.get("conc_20ms", 0.0)
    spread90 = e.get("spread_p90_ms")
    shape = dres.get("shape", "none")
    spec_corr = (dres.get("spectrum") or {}).get("corr_with_reference")

    conc5 = e.get("conc_5ms", 0.0)
    crest = dres.get("crest_db") or 0.0
    # A join click shows up either as a step that is unusual both locally and versus the
    # reference, or - when the material itself is transient dense and masks the step - as a
    # difference signal whose energy sits almost entirely inside +-1 ms of the join.
    impulse_like = conc1 >= CLICK_IMPULSE_CONC or (conc5 >= 0.9 and crest >= 20.0)
    step_outlier = sr >= CLICK_STEP_RATIO and (srr is None or srr >= CLICK_STEP_VS_REF)
    if peak_db >= CLICK_PEAK_DBFS and (impulse_like or step_outlier):
        verdict = "click"
        reasons.append("diff peak %.1f dBFS with %d%% of its energy within 1 ms of the seam "
                       "(%d%% within 5 ms); step %.2fx local p99.9, %.2fx reference"
                       % (peak_db, round(conc1 * 100), round(conc5 * 100), sr, srr or 0.0))
    elif rms_db <= QUIET_DIFF_DBFS:
        # The two renders are numerically the same file; a large step here is the programme's
        # own transient (it is in the reference too), not a join artefact.
        verdict = "no-audible-artifact"
        reasons.append("test and reference differ by %.1f dBFS RMS (below %.0f): no join "
                       "difference at all" % (rms_db, QUIET_DIFF_DBFS))
    elif rms_db > AMBIENT_DIFF_DBFS and sr < AMBIENT_MAX_STEP_RATIO and env_z < AMBIENT_MAX_ENV_Z \
            and (conc20 < 0.5 or (spread90 is not None and spread90 >= 20.0)):
        verdict = "ambient-shift"
        reasons.append("no step (%.2fx baseline), but diff is %.1f dBFS RMS and spread over "
                       "%.0f ms (p90); shape=%s%s"
                       % (sr, rms_db, spread90 or 0.0, shape,
                          "" if spec_corr is None else ", spec corr %.2f" % spec_corr))
    elif rms_db <= AMBIENT_DIFF_DBFS and sr < AMBIENT_MAX_STEP_RATIO and env_z < AMBIENT_MAX_ENV_Z \
            and flux_ratio < 2.0:
        verdict = "no-audible-artifact"
        reasons.append("diff %.1f dBFS RMS (below %.0f), step %.2fx, envelope z=%.1f, flux %.2fx"
                       % (rms_db, AMBIENT_DIFF_DBFS, sr, env_z, flux_ratio))
    else:
        verdict = "inconclusive"
        reasons.append("mixed evidence: step %.2fx, env z=%.1f, flux %.2fx, diff %.1f dBFS, "
                       "conc20 %.3f, shape=%s"
                       % (sr, env_z, flux_ratio, rms_db, conc20, shape))
    res["verdict"] = verdict
    res["reasons"] = reasons
    return res


# ------------------------------------------------------------------------ selftest
def build_selftest(src, keep=None):
    """Cuts src into three slices and rebuilds them with three kinds of join defect."""
    x, rate, info = read_wav(src)
    n = x.shape[0]
    seams = [n // 3, 2 * n // 3]
    clean = np.concatenate([x[:seams[0]], x[seams[0]:seams[1]], x[seams[1]:]], axis=0)
    click = clean.copy()
    click[seams[0]] += 0.005                   # one-sample discontinuity, -46 dBFS
    rng = np.random.default_rng(7)
    # Ambient shift: each slice renders with a slightly different level (-0.004 dB is
    # inaudible alone) plus a short decaying tail difference after every join.
    ambient = clean.copy()
    for i, s in enumerate(seams):
        seg = slice(s, seams[i + 1] if i + 1 < len(seams) else n)
        ambient[seg] *= 1.0 - 0.0005 * (i + 1)
        tail = int(0.2 * rate)
        t = np.arange(tail) / rate
        envl = np.exp(-t / 0.03)
        noise = rng.standard_normal((tail, x.shape[1]))
        filt = np.zeros_like(noise)
        acc = np.zeros(x.shape[1])
        for k in range(tail):
            acc = 0.7 * acc + 0.3 * noise[k]
            filt[k] = acc
        ambient[s:s + tail] += filt * envl[:, None] * 0.01
    outdir = keep or tempfile.mkdtemp(prefix="seam-selftest-")
    os.makedirs(outdir, exist_ok=True)
    paths = {}
    for name, data in (("clean", clean), ("click", click), ("ambient", ambient)):
        p = os.path.join(outdir, "gen_%s.wav" % name)
        write_wav24(p, data, rate)
        paths[name] = p
    return outdir, paths, seams, rate, info


# -------------------------------------------------------------------------- output
def summarise(report, quiet=False):
    lines = []
    if not quiet:
        r = report["ref"]
        t = report["test"]
        lines.append("reference: %s  %d Hz, %d ch, %s, %.3f s" % (
            r["path"], r["sample_rate"], r["channels"], r["format"], r["frames"] / r["sample_rate"]))
        lines.append("test     : %s  %d Hz, %d ch, %s, %.3f s" % (
            t["path"], t["sample_rate"], t["channels"], t["format"], t["frames"] / t["sample_rate"]))
        if r["sample_rate"] != t["sample_rate"] or r["channels"] != t["channels"]:
            lines.append("WARNING: format mismatch, comparing the common prefix")
        for w in report.get("warnings") or []:
            lines.append("WARNING: %s" % w)
        lines.append("seams    : %s   preroll %.3f s" % (
            ", ".join("%d (%.3f s)" % (s["seam_index"], s["seam_time_s"]) for s in report["seams"]),
            report["params"]["preroll_s"]))
        lines.append("")
        lines.append("%-9s %-6s %8s %8s %8s %8s %7s %9s %8s  %s" % (
            "seam", "time", "step/b", "step/ref", "envZ", "flux/b", "diffRMS", "conc20", "p90ms", "verdict"))
        for s in report["seams"]:
            st = s["step"]
            lines.append("%-9d %-6.2f %8s %8s %8.1f %8s %7.1f %9.3f %8s  %s" % (
                s["seam_index"], s["seam_time_s"],
                "%.2f" % st["ratio_vs_baseline"] if st["ratio_vs_baseline"] is not None else "-",
                "%.2f" % st["ratio_vs_reference"] if st["ratio_vs_reference"] is not None else "-",
                s["envelope"].get("z_score") or 0.0,
                "%.2f" % s["flux"]["ratio"] if s.get("flux") and s["flux"].get("ratio") is not None else "-",
                s["diff"].get("rms_dbfs", SILENCE),
                (s["diff"].get("energy") or {}).get("conc_20ms", 0.0),
                ("%.0f" % (s["diff"].get("energy") or {}).get("spread_p90_ms"))
                if (s["diff"].get("energy") or {}).get("spread_p90_ms") is not None else "-",
                s["verdict"]))
        lines.append("")
    for s in report["seams"]:
        lines.append("seam %d (%.3f s): %s" % (s["seam_index"], s["seam_time_s"], s["verdict"]))
        for why in s["reasons"]:
            lines.append("    %s" % why)
        st = s["step"]
        en = s["envelope"]
        fl = s["flux"] or {}
        d = s["diff"]
        lines.append("    step   test %.1f dBFS vs baseline p99.9 %.1f dBFS (x%s) / reference "
                     "%.1f dBFS (x%s)" % (
                         st["test_dbfs"], db(st["baseline_p999_test"]),
                         st["ratio_vs_baseline"], st["reference_dbfs"], st["ratio_vs_reference"]))
        lines.append("    env    max delta %.6f (%.2f dB), baseline %.6f+-%.6f, z=%s, rel=%.5f" % (
            en.get("max_abs_delta", 0.0), en.get("max_delta_db", 0.0),
            en.get("baseline_mean_abs", 0.0), en.get("baseline_std_abs", 0.0),
            en.get("z_score"), en.get("rel_jump") or 0.0))
        lines.append("    flux   %.4f vs p99.5 %.4f (x%s) at frame %s" % (
            fl.get("value", 0.0), fl.get("baseline_p995", 0.0), fl.get("ratio"), fl.get("frame")))
        lines.append("    diff   %.1f dBFS rms, %.1f dBFS peak, crest %.1f dB, shape=%s, "
                     "spec corr %s, flatness %s dB" % (
                         d.get("rms_dbfs", SILENCE), d.get("peak_dbfs", SILENCE),
                         d.get("crest_db", 0.0), d.get("shape", "none"),
                         (d.get("spectrum") or {}).get("corr_with_reference"),
                         (d.get("spectrum") or {}).get("flatness_db")))
        e = d.get("energy") or {}
        if "conc_1ms" in e:
            lines.append("    energy conc 1ms %.4f / 5ms %.4f / 20ms %.4f / 100ms %.4f, "
                         "spread p50 %.1f ms / p90 %.1f ms / p99 %.1f ms" % (
                             e["conc_1ms"], e["conc_5ms"], e["conc_20ms"], e["conc_100ms"],
                             e["spread_p50_ms"], e["spread_p90_ms"], e["spread_p99_ms"]))
    lines.append("")
    lines.append("overall: %s (%s)" % (
        report["overall"]["verdict"],
        ", ".join("%s=%d" % (k, v) for k, v in report["overall"]["verdict_counts"].items())))
    return "\n".join(lines)


def build_report(ref_path, test_path, seams, preroll_s, source=""):
    x, rate, tinfo = read_wav(test_path)
    r, rrate, rinfo = read_wav(ref_path)
    if rrate != rate:
        raise SystemExit("sample rate mismatch: reference %d Hz, test %d Hz" % (rrate, rate))
    if r.shape[1] != x.shape[1]:
        raise SystemExit("channel count mismatch: reference %d, test %d" % (r.shape[1], x.shape[1]))
    n = min(x.shape[0], r.shape[0])
    for s in seams:
        if not 1 <= s < n:
            raise SystemExit("seam %d is outside 1..%d (file is %.3f s long)"
                             % (s, n - 1, n / rate))
    warnings = []
    if x.shape[0] != r.shape[0]:
        warnings.append("length differs (test %d frames, reference %d); compared the common prefix"
                        % (x.shape[0], r.shape[0]))
    starts = [int(s) for s in seams]
    report = {
        "schema": "seam_analysis/1",
        "generated": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "source": source,
        "ref": rinfo, "test": tinfo,
        "warnings": warnings,
        "params": {
            "seams": starts, "preroll_s": preroll_s,
            "step_window_s": STEP_WINDOW_S, "step_percentile": STEP_PERCENTILE,
            "env_hop_s": ENV_HOP_S, "env_local_s": ENV_LOCAL_S, "env_baseline_s": ENV_BASE_S,
            "flux_n_fft": FLUX_N_FFT, "flux_hop": FLUX_HOP,
            "flux_window_s": FLUX_WINDOW_S, "flux_percentile": FLUX_PERCENTILE,
            "thresholds": {
                "click_step_ratio": CLICK_STEP_RATIO, "click_step_vs_ref": CLICK_STEP_VS_REF,
                "click_peak_dbfs": CLICK_PEAK_DBFS, "ambient_diff_dbfs": AMBIENT_DIFF_DBFS,
                "ambient_max_step_ratio": AMBIENT_MAX_STEP_RATIO,
                "ambient_max_env_z": AMBIENT_MAX_ENV_Z, "quiet_diff_dbfs": QUIET_DIFF_DBFS,
            },
        },
        "seams": [analyse_seam(x[:n], r[:n], s, rate, preroll_s) for s in starts],
    }
    counts = {}
    for s in report["seams"]:
        counts[s["verdict"]] = counts.get(s["verdict"], 0) + 1
    worst = max(report["seams"], key=lambda s: s["step"]["ratio_vs_baseline"] or 0.0)
    order = ["click", "ambient-shift", "inconclusive", "no-audible-artifact"]
    overall = next((v for v in order if counts.get(v)), "no-audible-artifact")
    report["overall"] = {
        "verdict": overall, "verdict_counts": counts,
        "max_step_ratio": worst["step"]["ratio_vs_baseline"],
        "max_step_ratio_seam": worst["seam_index"],
        "max_diff_rms_dbfs": max(s["diff"].get("rms_dbfs", SILENCE) for s in report["seams"]),
        "max_env_z": max((s["envelope"].get("z_score") or 0.0) for s in report["seams"]),
    }
    return report


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Seam quality analysis for segmented rendering (click vs ambient shift).")
    ap.add_argument("--ref", help="reference WAV: the whole file rendered in one go")
    ap.add_argument("--test", help="test WAV: the sliced render joined back together")
    ap.add_argument("--seams", help="comma separated sample indices, first sample of each next slice")
    ap.add_argument("--preroll", type=float, default=1.35,
                    help="seconds on each side of a seam analysed for the difference signal "
                         "(default 1.35)")
    ap.add_argument("--json", help="write the full report here ('-' for stdout)")
    ap.add_argument("--quiet", action="store_true", help="only print the verdict section")
    ap.add_argument("--selftest", metavar="WAV",
                    help="build clean/click/ambient variants of WAV in 3 slices, analyse them "
                         "and print a comparison")
    ap.add_argument("--keep", metavar="DIR", help="with --selftest: write the generated WAVs here")
    args = ap.parse_args(argv)

    if args.selftest:
        outdir, paths, seams, rate, _ = build_selftest(args.selftest, args.keep)
        print("selftest source : %s" % args.selftest)
        print("seams           : %s" % seams)
        print("generated       : %s%s" % (outdir, "" if args.keep else " (temporary, removed)"))
        summary = []
        for name in ("clean", "click", "ambient"):
            rep = build_report(paths["clean"], paths[name], seams, args.preroll,
                               source="selftest/%s" % name)
            summary.append((name, rep))
            print("")
            print("=" * 78)
            print("case: %s" % name)
            print(summarise(rep))
        print("")
        print("=" * 78)
        print("selftest summary")
        for name, rep in summary:
            print("  %-8s overall=%-20s seams=%s  (expected: %s)" % (
                name, rep["overall"]["verdict"],
                ",".join(s["verdict"] for s in rep["seams"]),
                {"clean": "no-audible-artifact", "click": "click",
                 "ambient": "ambient-shift"}[name]))
        if args.json:
            blob = json.dumps({n: r for n, r in summary}, indent=2, ensure_ascii=False)
            if args.json == "-":
                print(blob)
            else:
                with open(args.json, "w", encoding="utf-8") as fh:
                    fh.write(blob)
                print("json -> %s" % args.json)
        if not args.keep:
            shutil.rmtree(outdir, ignore_errors=True)
        return 0

    if not (args.ref and args.test and args.seams):
        ap.error("--ref, --test and --seams are required (or use --selftest)")
    try:
        seams = [int(p) for p in args.seams.replace(" ", "").split(",") if p != ""]
    except ValueError:
        ap.error("--seams takes comma separated integer sample indices, e.g. 960000,1920000")
    try:
        report = build_report(args.ref, args.test, seams, args.preroll)
    except (ValueError, OSError) as exc:
        raise SystemExit("error: %s" % exc)
    print(summarise(report, args.quiet))
    if args.json:
        blob = json.dumps(report, indent=2, ensure_ascii=False)
        if args.json == "-":
            print(blob)
        else:
            with open(args.json, "w", encoding="utf-8") as fh:
                fh.write(blob)
            print("json -> %s" % args.json)
    return 0


if __name__ == "__main__":
    sys.exit(main())
