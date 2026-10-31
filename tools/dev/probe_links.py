import lzma, gzip, os
A = "android/app/src/standalone/assets_orig/"
for name, opener in (("rootfs.bin", lzma.open), ("wine.bin", lzma.open), ("payload.bin", gzip.open)):
    syms, allp = [], set()
    with opener(A + name, "rb") as f:
        off = 0
        while True:
            hdr = f.read(512)
            if len(hdr) < 512 or hdr == b"\x00" * 512: break
            nm = hdr[0:100].split(b"\x00")[0].decode("utf-8", "replace")
            pre = hdr[345:500].split(b"\x00")[0].decode("utf-8", "replace")
            full = (pre + "/" + nm) if pre else nm
            size = int(hdr[124:136].split(b"\x00")[0].strip() or b"0", 8)
            typ = hdr[156:157]
            allp.add(full)
            lnk = hdr[157:257].split(b"\x00")[0].decode("utf-8", "replace")
            if typ == b"2": syms.append((full, lnk))
            left = ((size + 511) // 512) * 512
            while left > 0:
                b = f.read(min(1 << 20, left))
                if not b: break
                left -= len(b)
    offenders = []
    for s, t in syms:
        pre = s.rstrip("/") + "/"
        hits = [p for p in allp if p.startswith(pre)]
        if hits:
            offenders.append((s, t, hits[:2], len(hits)))
    print(name, "symlinks", len(syms), "symlink-paths-used-as-dirs:", len(offenders), offenders[:5])
    # also: are any archive paths *through* a symlink prefix even if the symlink
    # entry is absent? not possible; only real dirs create prefixes
