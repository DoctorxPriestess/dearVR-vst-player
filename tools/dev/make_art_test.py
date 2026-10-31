import struct, zlib, math, os, sys

out = sys.argv[1]
W = H = 512
rows = []
for y in range(H):
    row = bytearray([0])
    for x in range(W):
        r = int(60 + 120 * math.sin(x / W * 3.1))
        g = int(40 + 130 * math.cos(y / H * 2.4))
        b = int(90 + 110 * math.sin((x + y) / W * 2.0))
        d = math.hypot(x - 256, y - 256)
        if d < 110:
            r, g, b = 250, 45, 72
        elif d < 132:
            r, g, b = r // 3, g // 3, b // 3
        row += bytes((max(0, min(255, r)), max(0, min(255, g)), max(0, min(255, b))))
    rows.append(bytes(row))
raw = b"".join(rows)

def png_chunk(t, d):
    return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)

png = (b"\x89PNG\r\n\x1a\n"
       + png_chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
       + png_chunk(b"IDAT", zlib.compress(raw, 6))
       + png_chunk(b"IEND", b""))

def riff_chunk(t, d):
    pad = b"\x00" if len(d) & 1 else b""
    return t + struct.pack("<I", len(d)) + d + pad

sr, dur = 44100, 4
frames = bytearray()
for i in range(sr * dur):
    t = i / sr
    s = 0.20 * math.sin(2 * math.pi * 220 * t) + 0.13 * math.sin(2 * math.pi * 330 * t) + 0.09 * math.sin(2 * math.pi * 440 * t)
    env = min(1.0, t * 4) * min(1.0, (dur - t) * 3)
    v = int(max(-1.0, min(1.0, s * env)) * 32000)
    frames += struct.pack("<hh", v, v)
fmt = struct.pack("<HHIIHH", 1, 2, sr, sr * 4, 4, 16)

def id3_frame(fid, payload):
    return fid + struct.pack(">I", len(payload)) + b"\x00\x00" + payload

apic = id3_frame(b"APIC", b"\x00" + b"image/png" + b"\x00" + b"\x03" + b"\x00" + png)
tit2 = id3_frame(b"TIT2", b"\x00" + b"Art Test")
body = tit2 + apic
size = len(body)
ss = bytes(((size >> 21) & 0x7F, (size >> 14) & 0x7F, (size >> 7) & 0x7F, size & 0x7F))
id3 = b"ID3\x03\x00\x00" + ss + body

content = b"WAVE" + riff_chunk(b"fmt ", fmt) + riff_chunk(b"data", bytes(frames)) + riff_chunk(b"id3 ", id3)
with open(out, "wb") as f:
    f.write(b"RIFF" + struct.pack("<I", len(content)) + content)
print("wrote", out, os.path.getsize(out), "bytes; png", len(png), "id3", len(id3))
