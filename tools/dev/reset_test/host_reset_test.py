
import subprocess, os, sys, hashlib, time, json, wave, tempfile
import numpy as np

ROOT = r"D:\AI Misc\vst3_player"
EXE = os.path.join(ROOT, r"juce_renderer\build\vstrender_juce_artefacts\Release\vstrender_juce.exe")
PLUGIN = os.path.join(ROOT, r"build_payload\payload\dearVR PRO 2.vst3")
PRESETS = os.path.join(ROOT, r"build_payload\payload\presets")
BENCH = os.path.join(ROOT, r"tools\bench60.wav")
OUT = os.path.join(ROOT, r"tools\_reset_test\out")
os.makedirs(OUT, exist_ok=True)

def md5(p):
    h = hashlib.md5()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()

def load_wav(p):
    w = wave.open(p, "rb")
    n, ch, sw, sr = w.getnframes(), w.getnchannels(), w.getsampwidth(), w.getframerate()
    raw = w.readframes(n); w.close()
    if sw == 3:
        a = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        v = a[:, 0] | (a[:, 1] << 8) | (a[:, 2] << 16)
        v = np.where(v >= (1 << 23), v - (1 << 24), v).astype(np.float64) / float(1 << 23)
    elif sw == 2:
        v = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    elif sw == 4:
        v = np.frombuffer(raw, dtype="<i4").astype(np.float64) / float(1 << 31)
    else:
        raise SystemExit("unsupported sample width %d" % sw)
    return v.reshape(-1, ch), sr

def diff_stats(pa, pb):
    a, sa = load_wav(pa); b, sb = load_wav(pb)
    if a.shape != b.shape:
        return {"shape_a": list(a.shape), "shape_b": list(b.shape), "md5_equal": md5(pa) == md5(pb)}
    d = a - b
    rms = float(np.sqrt(np.mean(d * d)))
    mx = float(np.max(np.abs(d)))
    def db(x):
        return -999.0 if x <= 0 else 20.0 * np.log10(x)
    first = np.nonzero(np.any(d != 0, axis=1))[0]
    return {"md5_equal": md5(pa) == md5(pb), "rms_db": round(db(rms), 2), "max_db": round(db(mx), 2),
            "first_diff_sample": int(first[0]) if len(first) else None,
            "frames": int(a.shape[0]), "sr": sa}

class Serve:
    def __init__(self, extra=(), tag="serve"):
        self.tag = tag
        self.errpath = os.path.join(OUT, tag + ".err")
        self.err = open(self.errpath, "wb")
        self.t0 = time.perf_counter()
        self.p = subprocess.Popen([EXE, "-plugin", PLUGIN] + list(extra) + ["-serve"],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.err,
                                  text=True, bufsize=1)
        line = self.p.stdout.readline().strip()
        self.ready_ms = (time.perf_counter() - self.t0) * 1000.0
        if line != "READY":
            raise SystemExit("no READY, got %r (see %s)" % (line, self.errpath))
    def cmd(self, line):
        self.p.stdin.write(line + "\n"); self.p.stdin.flush()
        out = []
        while True:
            l = self.p.stdout.readline()
            if not l:
                raise SystemExit("EOF while waiting reply to %r" % line)
            l = l.rstrip("\r\n")
            out.append(l)
            if l.startswith("PROG "):
                continue
            return out
    def render(self, inp, outp):
        reps = self.cmd(inp + "|" + outp)
        last = reps[-1]
        if not last.startswith("OK"):
            raise SystemExit("render failed: %r" % reps)
        return outp
    def timeit(self, line, n=5):
        ts = []
        for _ in range(n):
            t0 = time.perf_counter()
            rep = self.cmd(line)[-1]
            ts.append((time.perf_counter() - t0) * 1000.0)
            if not rep.startswith("OK"):
                raise SystemExit("cmd %r failed: %r" % (line, rep))
        return sorted(ts)
    def close(self):
        try:
            self.p.stdin.write("QUIT\n"); self.p.stdin.flush()
            self.p.wait(timeout=15)
        except Exception:
            self.p.kill()
        self.err.close()

R = {}   # results
A = BENCH
B = os.path.join(OUT, "B_20s.wav")

# ---- generate B (20 s slice, same 48k/24bit format) ----
ff = r"D:\ASUS\Downloads\NVENCForge\tools\ffmpeg.exe"
if not os.path.exists(B):
    subprocess.run([ff, "-hide_banner", "-loglevel", "error", "-y", "-ss", "20", "-t", "20", "-i", A,
                    "-c:a", "pcm_s24le", B], check=True)

def fresh_render(inp, out, preset=None, tag="fresh"):
    args = [EXE, "-plugin", PLUGIN]
    if preset: args += ["-preset", preset]
    args += ["-in", inp, "-out", out]
    with open(os.path.join(OUT, tag + ".err"), "wb") as e:
        subprocess.run(args, stderr=e, check=True)
    return out

# ============ references from fresh processes ============
refA = fresh_render(A, os.path.join(OUT, "ref_A.wav"), tag="ref_A")
refB = fresh_render(B, os.path.join(OUT, "ref_B.wav"), tag="ref_B")
refAn = fresh_render(A, os.path.join(OUT, "ref_A2.wav"), tag="ref_A2")  # second fresh process determinism check
R["ref"] = {
    "A_md5": md5(A), "B_md5": md5(B),
    "ref_A_md5": md5(refA), "ref_A2_md5": md5(refAn), "ref_B_md5": md5(refB),
    "fresh_process_deterministic": md5(refA) == md5(refAn),
}

# ============ a: baseline, same process twice, no command ============
s = Serve(tag="a")
a1 = s.render(A, os.path.join(OUT, "a1.wav"))
a2 = s.render(A, os.path.join(OUT, "a2.wav"))
s.close()
R["a_baseline_no_reset"] = diff_stats(a1, a2)

# ============ b: render, RESET soft / hard / default, render ============
def reset_cycle(mode, tag):
    s = Serve(tag=tag)
    o1 = s.render(A, os.path.join(OUT, tag + "_1.wav"))
    rep = s.cmd("RESET" + (("|" + mode) if mode else ""))[-1]
    o2 = s.render(A, os.path.join(OUT, tag + "_2.wav"))
    s.close()
    return {"reset_reply": rep, "second_vs_first": diff_stats(o1, o2), "first_vs_fresh": diff_stats(o1, refA)}

R["b_reset_soft"] = reset_cycle("soft", "b_soft")
R["b_reset_hard"] = reset_cycle("hard", "b_hard")
R["b_reset_default"] = reset_cycle("", "b_default")

# ============ c: A -> RESET -> B -> RESET -> A ============
def cross_file(cmdline, tag):
    s = Serve(tag=tag)
    cA1 = s.render(A, os.path.join(OUT, tag + "_A1.wav"))
    s.cmd(cmdline)
    cB = s.render(B, os.path.join(OUT, tag + "_B.wav"))
    s.cmd(cmdline)
    cA2 = s.render(A, os.path.join(OUT, tag + "_A2.wav"))
    s.close()
    return {"A1_vs_A2": diff_stats(cA1, cA2), "A2_vs_fresh_A": diff_stats(cA2, refA),
            "B_vs_fresh_B": diff_stats(cB, refB)}
R["c_cross_soft"] = cross_file("RESET|soft", "c_soft")
R["c_cross_hard"] = cross_file("RESET|hard", "c_hard")
R["c_cross_default"] = cross_file("RESET", "c_default")

# ============ e: state retention ============
def params_dump(s, tag):
    jp = os.path.join(OUT, tag + "_params.json")
    reps = s.cmd("PARAMS|" + jp)
    if not reps[-1].startswith("OK"):
        raise SystemExit("PARAMS failed: %r" % reps)
    with open(jp, "r", encoding="utf-8") as f:
        d = json.load(f)
    return {p["id"]: (p["norm"], p["text"]) for p in d["params"]}

def state_retention(mode, tag, preset=None):
    s = Serve(tag=tag)
    if preset:
        rep = s.cmd("PRESET|" + preset)[-1]
        if not rep.startswith("OK"):
            raise SystemExit("PRESET failed: %r" % rep)
    p0 = params_dump(s, tag + "_before")
    # pick a parameter with a meaningful range for SET
    pid = None
    for k, v in p0.items():
        if 0.0 < v[0] < 1.0:
            pid = k; break
    get0 = s.cmd("GET|" + pid)[-1] if pid else None
    setrep = s.cmd("SET|%s|0.25" % pid)[-1] if pid else None
    get1 = s.cmd("GET|" + pid)[-1] if pid else None
    p_set = params_dump(s, tag + "_postset")
    rrep = s.cmd("RESET|" + mode)[-1]
    rrep2 = s.cmd("RESET|" + mode)[-1]
    get2 = s.cmd("GET|" + pid)[-1] if pid else None
    p1 = params_dump(s, tag + "_after")
    s.close()
    changed = [k for k in p_set if p_set[k] != p1.get(k)]
    changed_vs_unset = [k for k in p0 if p0[k] != p1.get(k)]
    return {"reset_reply": rrep, "param": pid, "get_before_set": get0, "set_reply": setrep,
            "get_after_set": get1, "get_after_reset": get2,
            "get_preserved": get1 == get2,
            "two_resets_ok": rrep2.startswith("OK"),
            "params_after_reset_equal": p_set == p1,
            "unexpected_param_changes": [k for k in changed_vs_unset if k != pid],
            "params_changed_count": len(changed),
            "params_changed_sample": changed[:5]}

R["e_param_retention_soft"] = state_retention("soft", "e_soft")
R["e_param_retention_hard"] = state_retention("hard", "e_hard")

# preset retention: compare param table with fresh process loading the same preset
preset_path = os.path.join(PRESETS, "Cathedral.vstpreset")
if not os.path.exists(preset_path):
    cands = [f for f in os.listdir(PRESETS) if f.endswith(".vstpreset")]
    preset_path = os.path.join(PRESETS, sorted(cands)[0])
R["preset_used"] = os.path.basename(preset_path)

def preset_table_soft(mode, tag):
    s = Serve(tag=tag)
    s.cmd("PRESET|" + preset_path)
    p0 = params_dump(s, tag + "_pre")
    s.cmd("RESET|" + mode)
    p1 = params_dump(s, tag + "_post")
    s.close()
    return {"params_equal": p0 == p1, "changed": [k for k in p0 if p0[k] != p1.get(k)][:8]}

R["e_preset_retention_soft"] = preset_table_soft("soft", "ep_soft")
R["e_preset_retention_hard"] = preset_table_soft("hard", "ep_hard")

# fresh process with preset -> ground truth table
s = Serve(extra=["-preset", preset_path], tag="ep_fresh")
pref = params_dump(s, "ep_fresh")
s.close()
s = Serve(tag="ep_fresh2")
s.cmd("PRESET|" + preset_path)
pvia = params_dump(s, "ep_fresh2")
s.close()
R["e_preset_fresh_vs_cmd_load"] = {"equal": pref == pvia}

# ============ g: sample-rate change around RESET (curSr must follow) ============
C = os.path.join(OUT, "C_44k_5s.wav")
if not os.path.exists(C):
    subprocess.run([ff, "-hide_banner", "-loglevel", "error", "-y", "-t", "5", "-i", A,
                    "-ar", "44100", "-c:a", "pcm_s24le", C], check=True)
refC = fresh_render(C, os.path.join(OUT, "ref_C.wav"), tag="ref_C")

def sr_change(cmdline, tag):
    s = Serve(tag=tag)
    s.render(A, os.path.join(OUT, tag + "_A.wav"))
    s.cmd(cmdline)
    c1 = s.render(C, os.path.join(OUT, tag + "_C1.wav"))
    s.cmd(cmdline)
    c2 = s.render(C, os.path.join(OUT, tag + "_C2.wav"))
    s.close()
    return {"C1_vs_fresh": diff_stats(c1, refC), "C2_vs_fresh": diff_stats(c2, refC),
            "C1_vs_C2": diff_stats(c1, c2)}

R["g_sr_change_reset_default"] = sr_change("RESET", "g_default")
R["g_sr_change_reset_hard"] = sr_change("RESET|hard", "g_hard")

# ============ h: repeated RESET stability ============
s = Serve(tag="h_loop")
reps = [s.cmd("RESET")[-1] for _ in range(20)]
o = s.render(A, os.path.join(OUT, "h_after20.wav"))
s.close()
R["h_20_resets_then_render"] = {"all_ok": all(r == "OK" for r in reps),
                                "vs_fresh_A": diff_stats(o, refA)}

# ============ i: legacy command compatibility ============
def legacy_compat():
    s = Serve(tag="i_legacy")
    outp = os.path.join(OUT, "i_legacy_P.wav")
    replies = s.cmd(A + "|" + outp + "|" + preset_path)
    progs = [r for r in replies if r.startswith("PROG ")]
    savep = os.path.join(OUT, "i_saved.vstpreset")
    srep = s.cmd("SAVE|" + savep)[-1]
    s.close()
    return {"prog_count": len(progs), "prog_values": [int(p.split()[1]) for p in progs],
            "legacy_reply": replies[-1], "save_reply": srep,
            "save_exists": os.path.exists(savep),
            "save_size": os.path.getsize(savep) if os.path.exists(savep) else 0}

R["i_legacy_compat"] = legacy_compat()
refP = fresh_render(A, os.path.join(OUT, "ref_P.wav"), preset=preset_path, tag="ref_P")
R["i_legacy_preset_render_vs_fresh"] = diff_stats(os.path.join(OUT, "i_legacy_P.wav"), refP)

# ============ f: timings ============
s = Serve(tag="f_timing")
R["timing_reset_soft_ms"] = s.timeit("RESET|soft", 5)
R["timing_reset_hard_ms"] = s.timeit("RESET|hard", 5)
R["timing_default_reset_ms"] = s.timeit("RESET", 5)
s.close()

cold = []
for i in range(5):
    t0 = time.perf_counter()
    s = Serve(tag="cold%d" % i)
    cold.append((time.perf_counter() - t0) * 1000.0)
    s.close()
R["cold_start_ms"] = sorted(cold)

def med(v):
    return v[len(v) // 2]

R["summary"] = {
    "reset_soft_median_ms": round(med(R["timing_reset_soft_ms"]), 1),
    "reset_hard_median_ms": round(med(R["timing_reset_hard_ms"]), 1),
    "reset_default_median_ms": round(med(R["timing_default_reset_ms"]), 1),
    "cold_start_median_ms": round(med(R["cold_start_ms"]), 1),
}

with open(os.path.join(OUT, "results.json"), "w", encoding="utf-8") as f:
    json.dump(R, f, indent=1, ensure_ascii=False)
print(json.dumps(R, indent=1, ensure_ascii=False))
