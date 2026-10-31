
import lzma, gzip, tarfile, collections

def scan(path, opener, label):
    total = reg = hard = hard_bytes = dirs = syms = n = 0
    top = collections.Counter()
    nlink_files = collections.Counter()
    with opener(path, 'rb') as raw:
        tf = tarfile.open(fileobj=raw, mode='r|')
        for m in tf:
            n += 1
            if m.isdir(): dirs += 1
            elif m.issym(): syms += 1
            elif m.islnk():
                hard += 1; hard_bytes += m.size
            elif m.isfile():
                reg += 1; total += m.size
                parts = m.name.split('/')
                key = '/'.join(parts[:2]) if len(parts) > 1 else parts[0]
                top[key] += m.size
    print("== " + label)
    print("  entries={} dirs={} syms={} regular={} regular_MB={:.0f}".format(n, dirs, syms, reg, total/1048576))
    print("  hardlinks={} hardlink_MB={:.0f}".format(hard, hard_bytes/1048576))
    for k, v in top.most_common(10):
        print("    {:8.1f} MB  {}".format(v/1048576, k))
    return total

scan("android/app/src/standalone/assets/rootfs.bin", lzma.open, "rootfs.bin")
scan("android/app/src/standalone/assets/wine.bin", lzma.open, "wine.bin")
scan("android/app/src/standalone/assets/payload.bin", gzip.open, "payload.bin")
