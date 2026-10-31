#!/usr/bin/env python3
"""
PC-side model of the Android extraction loop: measures single-thread streaming
extract vs entry-aligned parallel extract of the split assets.

This mirrors core/FastExtract.java: every segment is decoded by its own thread and
turns into files under a destination root (mkdirs, regular files, symlinks,
hardlinks, chmod +x).  Wall clock on a PC SSD is not the phone's flash, so use the
ratio, not the absolute numbers.

Usage: python tools/bench_extract.py [--k 6] [--work DIR]
"""
import argparse, gzip, hashlib, lzma, os, shutil, statistics, tarfile, time
from concurrent.futures import ThreadPoolExecutor

ASSETS = os.path.join("android", "app", "src", "standalone", "assets")
ORIG = os.path.join("android", "app", "src", "standalone", "assets_orig")


def asset_orig(stem):
    p = os.path.join(ORIG, stem + ".bin")
    return p if os.path.exists(p) else os.path.join(ASSETS, stem + ".bin")
SPECS = [("rootfs", "xz"), ("wine", "xz"), ("payload", "gz")]
BUF = 1 << 20


def write_entry(tf, m, dest):
    out = os.path.join(dest, m.name)
    parent = os.path.dirname(out)
    if parent:
        os.makedirs(parent, exist_ok=True)
    if m.isdir():
        os.makedirs(out, exist_ok=True)
    elif m.issym():
        if os.path.lexists(out):
            os.unlink(out)
        try:
            os.symlink(m.linkname, out)
        except OSError:
            pass
    elif m.islnk():
        if os.path.lexists(out):
            os.unlink(out)
        try:
            os.link(os.path.join(dest, m.linkname), out)
        except OSError:
            src = os.path.join(dest, m.linkname)
            if os.path.isfile(src):
                shutil.copyfile(src, out)
    elif m.isfile():
        # Windows-only accommodation: rootfs holds case-differing names
        # (hp70092a symlink + hp70092A file), which collide on a case-insensitive
        # filesystem. Android's ext4/f2fs does not collide, so this is bench-local.
        if os.path.lexists(out):
            try:
                os.unlink(out)
            except OSError:
                pass
        with tf.extractfile(m) as fi, open(out, "wb") as fo:
            shutil.copyfileobj(fi, fo, BUF)
        if m.mode & 0o111:
            os.chmod(out, 0o755)


def extract_stream(path, opener, dest):
    t0 = time.time()
    n = 0
    with opener(path, "rb") as f, tarfile.open(fileobj=f, mode="r|") as tf:
        for m in tf:
            write_entry(tf, m, dest)
            n += 1
    return time.time() - t0, n


def extract_segments(seg_paths, opener, dest, k):
    t0 = time.time()

    def one(p):
        with opener(p, "rb") as f, tarfile.open(fileobj=f, mode="r|") as tf:
            for m in tf:
                write_entry(tf, m, dest)
        return 1

    with ThreadPoolExecutor(max_workers=k) as ex:
        list(ex.map(one, seg_paths))
    return time.time() - t0


def rm(d):
    shutil.rmtree(d, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--k", type=int, default=6)
    ap.add_argument("--work", default=os.path.join(os.environ.get("TEMP", "."), "vst_extract_bench"))
    args = ap.parse_args()
    if os.path.exists(args.work):
        rm(args.work)
    os.makedirs(args.work, exist_ok=True)
    openers = {"xz": lzma.open, "gz": gzip.open}

    # decode-only baseline (no disk writes): isolates the CPU cost
    t0 = time.time()
    bytes_decoded = 0
    for stem, codec in SPECS:
        with openers[codec](asset_orig(stem), "rb") as f:
            while True:
                b = f.read(BUF)
                if not b:
                    break
                bytes_decoded += len(b)
    t_dec_serial = time.time() - t0
    print("decode-only, 1 thread      : %6.2fs  (%.0f MB, %.1f MB/s)"
          % (t_dec_serial, bytes_decoded / 1048576, bytes_decoded / 1048576 / t_dec_serial))

    # serial full extract
    t_ser = 0.0
    for stem, codec in SPECS:
        d = os.path.join(args.work, "serial", stem)
        os.makedirs(d, exist_ok=True)
        dt, n = extract_stream(asset_orig(stem), openers[codec], d)
        print("serial  %-8s           : %6.2fs  %d entries" % (stem, dt, n))
        t_ser += dt
    print("serial full extract total  : %6.2fs" % t_ser)

    # parallel full extract
    t_par = 0.0
    for stem, codec in SPECS:
        d = os.path.join(args.work, "parallel", stem)
        os.makedirs(d, exist_ok=True)
        segs = [os.path.join(ASSETS, "%s_%dof%d.bin" % (stem, i, args.k)) for i in range(args.k)]
        dt = extract_segments(segs, openers[codec], d, args.k)
        print("parallel %-8s (%d thr)   : %6.2fs" % (stem, args.k, dt))
        t_par += dt
    print("parallel full extract total: %6.2fs" % t_par)
    print()
    print("speedup (full extract, PC) : %.2fx   (decode-only floor %.2fx)"
          % (t_ser / t_par, t_dec_serial / (t_dec_serial / args.k)))
    print("speedup vs serial decode   : %.2fx" % (t_dec_serial / t_par))

    # equivalence spot check between the two trees
    def fingerprint(root):
        items = []
        for base, dirs, files in os.walk(root):
            for f in files:
                p = os.path.join(base, f)
                rel = os.path.relpath(p, root)
                st = os.lstat(p)
                if os.path.islink(p):
                    items.append((rel, "sym", os.readlink(p)))
                else:
                    items.append((rel, "file", st.st_size, st.st_mode & 0o777))
        return sorted(items)

    ok = True
    for stem, _ in SPECS:
        a = fingerprint(os.path.join(args.work, "serial", stem))
        b = fingerprint(os.path.join(args.work, "parallel", stem))
        same = a == b
        ok = ok and same
        print("%-8s extracted tree identical: %s (%d files)" % (stem, "PASS" if same else "FAIL", len(a)))
    print()
    print("RESULT: %s" % ("PASS" if ok else "FAIL"))
    rm(args.work)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
