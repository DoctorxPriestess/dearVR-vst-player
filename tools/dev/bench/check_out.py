import wave, os, math, numpy as np
D = r"D:/AI Misc/vst3_player/tools/dev/bench/out"
for n in ("src_alac24_vst", "src_alac16_vst", "src_aac_vst", "src_flac24_vst", "src_flac16_vst"):
    p = os.path.join(D, n + ".wav")
    w = wave.open(p, "rb")
    ch, sw, rate, frames = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
    raw = w.readframes(frames); w.close()
    bits = sw * 8
    if bits == 24:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        v = b[:,0] | (b[:,1]<<8) | (b[:,2]<<16); v = np.where(v & 0x800000, v - 0x1000000, v); fs = 2.0**23
    else:
        v = np.frombuffer(raw, dtype="<i2").astype(np.int32); fs = 2.0**15
    peak = float(np.abs(v).max()); rms = float(np.sqrt((v.astype(np.float64)**2).mean()))
    print("%-18s %dch %dbit %dHz %6.1fs peak %6.1f dBFS rms %6.1f dBFS" % (n, ch, bits, rate, frames/rate, 20*math.log10(peak/fs), 20*math.log10(rms/fs)))
