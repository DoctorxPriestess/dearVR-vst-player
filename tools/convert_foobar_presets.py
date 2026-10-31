import struct, os
SRC = "C:/Users/ControlxSaria/AppData/Roaming/foobar2000-v2/themes/audio-archive/columns"
OUT = "D:/AI Misc/vst3_player/tools/presets_out"
names = ["Classical", "HD2", "Piano_solo", "Pop"]
os.makedirs(OUT, exist_ok=True)

def foobar_xml(path):
    d = open(path, "rb").read()
    assert d[:4] == b"VST3"
    off = 48
    while off + 8 <= len(d):
        cid = d[off:off+4]
        size = struct.unpack("<I", d[off+4:off+8])[0]
        if off + 8 + size > len(d):
            break
        if cid == b"VC2!":
            return d[off+8:off+8+size]
        off += 8 + size
    raise SystemExit("no VC2! chunk in " + path)

def wrap(comp):
    out = bytearray(48); out[0:4] = b"VST3"
    oc = len(out); out += comp
    oo = len(out)
    tbl = len(out); out += bytearray(60)
    for i, (cid, off, sz) in enumerate([(b"Comp", oc, len(comp)), (b"Cont", oo, 0), (b"Info", oo, 0)]):
        out[tbl+i*20:tbl+i*20+4] = cid
        out[tbl+i*20+4:tbl+i*20+12] = struct.pack("<q", off)
        out[tbl+i*20+12:tbl+i*20+20] = struct.pack("<q", sz)
    return bytes(out)

for n in names:
    xml = foobar_xml(os.path.join(SRC, n + ".vstpreset"))
    comp = b"VC2!" + struct.pack("<I", len(xml)) + xml
    p = os.path.join(OUT, n + ".vstpreset")
    open(p, "wb").write(wrap(comp))
    print(n, "xml", len(xml), "comp", len(comp), "file", os.path.getsize(p))
