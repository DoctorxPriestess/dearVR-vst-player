#!/usr/bin/env python3
"""
Prove that splitting preserved the tar payload exactly.

Checks per asset:
  A. byte level   : concat(xz/gz-decode(seg_0..seg_K-1)) == decode(original .bin)
  B. sha256       : that concatenation hashes to the manifest's segment hashes
  C. semantic     : each segment is parsed as its own tar stream; the union of
                    (path, type, mode, size, content-sha256 / link target) equals
                    the set extracted from the original single-stream asset.

Exit code 0 = PASS, 1 = FAIL.
"""
import gzip, hashlib, json, lzma, os, sys, tarfile

ASSETS = os.path.join("android", "app", "src", "standalone", "assets")
ORIG = os.path.join("android", "app", "src", "standalone", "assets_orig")
MANIFEST = os.path.join("tools", "split_manifest.json")


def content_digest(f):
    h = hashlib.sha256()
    while True:
        b = f.read(1 << 20)
        if not b:
            break
        h.update(b)
    return h.hexdigest()


def scan(stream, label):
    """Return {path: (type, mode, size, digest-or-linkname)} for one tar stream."""
    out = {}
    with tarfile.open(fileobj=stream, mode="r|") as tf:
        for m in tf:
            if m.isdir():
                key = (m.name, "dir", m.mode)
                out[m.name] = key
            elif m.issym():
                out[m.name] = (m.name, "sym", m.mode, m.linkname)
            elif m.islnk():
                out[m.name] = (m.name, "hard", m.mode, m.linkname)
            elif m.isfile():
                out[m.name] = (m.name, "file", m.mode, m.size, content_digest(tf.extractfile(m)))
            else:
                out[m.name] = (m.name, "other:" + m.type.decode("utf-8", "replace"))
    return out


def main():
    man = json.load(open(MANIFEST))
    failures = []
    for stem, info in man.items():
        codec = info["codec"]
        opener = lzma.open if codec == "xz" else gzip.open
        orig_path = os.path.join(ORIG, stem + ".bin")
        if not os.path.exists(orig_path):
            orig_path = os.path.join(ASSETS, stem + ".bin")
        seg_paths = [os.path.join(ASSETS, s["file"]) for s in info["segments"]]

        h = hashlib.sha256()
        total = 0
        for p, s in zip(seg_paths, info["segments"]):
            seg_h = hashlib.sha256()
            n = 0
            with opener(p, "rb") as f:
                while True:
                    b = f.read(1 << 20)
                    if not b:
                        break
                    seg_h.update(b)
                    h.update(b)
                    n += len(b)
            if n != s["size"]:
                failures.append("%s: seg %d size %d != manifest %d" % (stem, s["i"], n, s["size"]))
            if seg_h.hexdigest() != s["sha256_uncompressed"]:
                failures.append("%s: seg %d uncompressed sha mismatch" % (stem, s["i"]))
            total += n
        concat_sha = h.hexdigest()

        with opener(orig_path, "rb") as f:
            oh = hashlib.sha256()
            on = 0
            while True:
                b = f.read(1 << 20)
                if not b:
                    break
                oh.update(b)
                on += len(b)

        ok_bytes = concat_sha == oh.hexdigest()
        ok_len = total == on
        print("== %s: %d segments, concat=%d bytes, original=%d bytes" % (stem, len(seg_paths), total, on))
        print("   A byte-identical: %s   sha=%s" % ("PASS" if ok_bytes and ok_len else "FAIL", concat_sha[:16]))
        if not (ok_bytes and ok_len):
            failures.append("%s: byte-level mismatch" % stem)

        with opener(orig_path, "rb") as f:
            a = scan(f, stem)
        b = {}
        for p in seg_paths:
            with opener(p, "rb") as f:
                s = scan(f, os.path.basename(p))
            dup = set(b) & set(s)
            if dup:
                failures.append("%s %s: duplicate paths across segments: %s" % (stem, os.path.basename(p), list(dup)[:3]))
            b.update(s)
        ok_sem = a == b
        if ok_sem:
            print("   B semantic-identical: PASS  (%d entries, files/dirs/symlinks/hardlinks all equal)" % len(a))
        else:
            only_a = [k for k in a if a[k] != b.get(k)][:5]
            only_b = [k for k in b if k not in a][:5]
            print("   B semantic-identical: FAIL  diff_a=%s diff_b=%s" % (only_a, only_b))
            failures.append("%s: semantic mismatch" % stem)

    print()
    if failures:
        print("RESULT: FAIL")
        for f in failures:
            print("  - " + f)
        return 1
    print("RESULT: PASS  (all assets: segment concat == original tar payload, byte for byte)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
