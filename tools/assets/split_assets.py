#!/usr/bin/env python3
"""
Split a compressed tar asset into K entry-aligned segments, each an independent
xz (or gzip) stream, so the Android side can decode segments in parallel.

Why this is valid: a tar file is a sequence of 512-byte header blocks followed by
data blocks.  Cutting the *uncompressed* byte stream exactly at a tar header
boundary yields K byte ranges that are each a valid standalone tar stream (the
first entry of segment i is a normal tar entry; a tar reader stops at the zero
blocks at the end of the last range).

Output naming: <base>_<i>of<K>.bin  (e.g. rootfs_0of6.bin .. rootfs_5of6.bin).
The count is encoded in the name so the reader can verify it has a complete,
self-consistent set and fall back to the legacy single-stream asset otherwise.
The .bin suffix is kept on purpose so the AGP 'noCompress += "bin"' rule still
matches and the segments stay stored (uncompressed) inside the APK.

Usage:
  python tools/split_assets.py                 # split all three assets, K=6
  python tools/split_assets.py --dry-run       # show the plan only
  python tools/split_assets.py rootfs          # one asset (name stem)
"""
import argparse, gzip, hashlib, json, lzma, os, sys, time
from concurrent.futures import ThreadPoolExecutor

ASSETS = os.path.join("android", "app", "src", "standalone", "assets")
ORIG = os.path.join("android", "app", "src", "standalone", "assets_orig")
ZERO = b"\x00" * 512
META_TYPES = (b"x", b"X", b"g", b"L", b"K")   # pax / GNU long name+link headers


def decompress_all(path, opener):
    with opener(path, "rb") as f:
        return f.read()


def parse_tar_groups(data):
    """Return [(group_start_offset, name)] where group_start is the offset of the
    first header (incl. any pax/GNU long-name meta headers) of a logical entry."""
    groups = []
    pos = 0
    pending = None
    n = len(data)
    while pos + 512 <= n:
        blk = data[pos:pos + 512]
        if blk == ZERO:
            break
        name = blk[0:100].split(b"\x00")[0].decode("utf-8", "replace")
        prefix = blk[345:500].split(b"\x00")[0].decode("utf-8", "replace")
        full = (prefix + "/" + name) if prefix else name
        try:
            size = int(blk[124:136].split(b"\x00")[0].strip() or b"0", 8)
        except ValueError:
            raise SystemExit("bad tar at offset %d (%r)" % (pos, blk[:16]))
        typ = blk[156:157]
        if typ in META_TYPES:
            if pending is None:
                pending = pos
        else:
            groups.append((pending if pending is not None else pos, full))
            pending = None
        pos += 512 + ((size + 511) // 512) * 512
    return groups, pos


def pick_boundaries(groups, total, k):
    if k < 2:
        return []
    cands = sorted({g[0] for g in groups if g[0] > 0})
    bounds = []
    for i in range(1, k):
        target = total * i // k
        best = min(cands, key=lambda c: (abs(c - target), c))
        if bounds and best <= bounds[-1]:
            # push forward to the next unused candidate
            nxt = [c for c in cands if c > bounds[-1]]
            if not nxt:
                break
            best = nxt[0]
        bounds.append(best)
    return bounds


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("only", nargs="*", help="asset name stems (default: all)")
    ap.add_argument("--k", type=int, default=6)
    ap.add_argument("--preset", type=int, default=6, help="xz preset for .xz assets")
    ap.add_argument("--gzip-level", type=int, default=6)
    ap.add_argument("--assets", default=ASSETS)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    specs = [("rootfs", "xz"), ("wine", "xz"), ("payload", "gz")]
    if args.only:
        specs = [s for s in specs if s[0] in set(args.only)]
    manifest = {}
    for stem, codec in specs:
        src = os.path.join(ORIG, stem + ".bin")
        if not os.path.exists(src):
            src = os.path.join(args.assets, stem + ".bin")
        if not os.path.exists(src):
            print("SKIP missing " + stem + ".bin (looked in assets_orig/ and assets/)")
            continue
        opener = lzma.open if codec == "xz" else gzip.open
        t0 = time.time()
        data = decompress_all(src, opener)
        t1 = time.time()
        groups, tar_end = parse_tar_groups(data)
        total = len(data)
        bounds = pick_boundaries(groups, total, args.k)
        edges = [0] + bounds + [total]
        print("== %s: %d entries, uncompressed %d bytes (%.1f MB), decode %.2fs"
              % (stem, len(groups), total, total / 1048576, t1 - t0))
        print("   tar payload end at %d, trailing %d bytes" % (tar_end, total - tar_end))
        parts = []
        for i in range(len(edges) - 1):
            a, b = edges[i], edges[i + 1]
            first = next((g[1] for g in groups if g[0] == a), "<mid?>" if a else groups[0][1])
            parts.append({"i": i, "start": a, "end": b, "size": b - a, "first_entry": first})
            print("   seg %d: [%10d,%10d) %8.1f MB  first=%s" % (i, a, b, (b - a) / 1048576, first))
        if args.dry_run:
            continue

        k = len(parts)

        def compress_one(p):
            seg = data[p["start"]:p["end"]]
            if codec == "xz":
                blob = lzma.compress(seg, preset=args.preset)
            else:
                blob = gzip.compress(seg, compresslevel=args.gzip_level, mtime=0)
            out = os.path.join(args.assets, "%s_%dof%d.bin" % (stem, p["i"], k))
            with open(out, "wb") as f:
                f.write(blob)
            p["out"] = out
            p["comp"] = len(blob)
            p["sha256_uncompressed"] = hashlib.sha256(seg).hexdigest()
            return p

        t2 = time.time()
        with ThreadPoolExecutor(max_workers=4) as ex:
            parts = list(ex.map(compress_one, parts))
        t3 = time.time()
        comp_total = sum(p["comp"] for p in parts)
        print("   compressed total %d bytes (%.1f MB) in %.1fs; original %d bytes"
              % (comp_total, comp_total / 1048576, t3 - t2, os.path.getsize(src)))
        manifest[stem] = {
            "codec": codec, "k": len(parts), "uncompressed": total,
            "original_asset_bytes": os.path.getsize(src),
            "original_asset_sha256": hashlib.sha256(open(src, "rb").read()).hexdigest(),
            "compressed_bytes": comp_total,
            "segments": [{k: v for k, v in (("i", p["i"]), ("start", p["start"]), ("end", p["end"]),
                                            ("size", p["size"]), ("compressed", p["comp"]),
                                            ("sha256_uncompressed", p["sha256_uncompressed"]),
                                            ("file", os.path.basename(p["out"]))) } for p in parts],
        }
        del data
    if not args.dry_run and manifest:
        path = os.path.join("tools", "split_manifest.json")
        old = {}
        if os.path.exists(path):
            old = json.load(open(path))
        old.update(manifest)
        with open(path, "w") as f:
            json.dump(old, f, indent=2)
        print("manifest -> " + path)


if __name__ == "__main__":
    main()
