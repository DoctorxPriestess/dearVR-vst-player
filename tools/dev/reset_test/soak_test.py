
import subprocess, os, hashlib, time, json, ctypes, ctypes.wintypes
ROOT = r"D:\AI Misc\vst3_player"; OUT = os.path.join(ROOT, r"tools\_reset_test\out")
PLUGIN = os.path.join(ROOT, r"build_payload\payload\dearVR PRO 2.vst3")
A = os.path.join(ROOT, r"tools\bench60.wav")
EXE = os.path.join(ROOT, r"juce_renderer\build\vstrender_juce_artefacts\Release\vstrender_juce.exe")
REF='d5c1b19add38445d773609691f49aeae'
def md5(p):
    h=hashlib.md5()
    with open(p,'rb') as f:
        for b in iter(lambda: f.read(1<<20), b''): h.update(b)
    return h.hexdigest()
class PMC_EX(ctypes.Structure):
    _fields_=[("cb",ctypes.c_ulong),("PageFaultCount",ctypes.c_ulong),
              ("PeakWorkingSetSize",ctypes.c_size_t),("WorkingSetSize",ctypes.c_size_t),
              ("QuotaPeakPagedPoolUsage",ctypes.c_size_t),("QuotaPagedPoolUsage",ctypes.c_size_t),
              ("QuotaPeakNonPagedPoolUsage",ctypes.c_size_t),("QuotaNonPagedPoolUsage",ctypes.c_size_t),
              ("PagefileUsage",ctypes.c_size_t),("PeakPagefileUsage",ctypes.c_size_t),
              ("PrivateUsage",ctypes.c_size_t)]
def mem(pid):
    h=ctypes.windll.kernel32.OpenProcess(0x1000|0x0400,False,pid)  # QUERY_INFORMATION|LIMITED
    c=PMC_EX(); c.cb=ctypes.sizeof(c)
    ctypes.windll.psapi.GetProcessMemoryInfo(h,ctypes.byref(c),ctypes.sizeof(c))
    ctypes.windll.kernel32.CloseHandle(h)
    return int(c.WorkingSetSize), int(c.PrivateUsage)
err=open(os.path.join(OUT,'soak.err'),'wb')
p=subprocess.Popen([EXE,'-plugin',PLUGIN,'-serve'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=err,text=True,bufsize=1)
assert p.stdout.readline().strip()=='READY'
def cmd(c):
    p.stdin.write(c+'\n'); p.stdin.flush()
    while True:
        l=p.stdout.readline()
        if not l: raise SystemExit('EOF '+c)
        l=l.rstrip('\r\n')
        if not l.startswith('PROG '): return l
snaps={}
snaps['start']=mem(p.pid)
t0=time.perf_counter(); n=0
for target in (50,150,300):
    while n<target:
        assert cmd('RESET')=='OK', 'reset failed at %d'%n
        n+=1
    snaps['after_%d'%target]=mem(p.pid)
soak_ms=(time.perf_counter()-t0)*1000
o=os.path.join(OUT,'soak_render.wav'); assert cmd(A+'|'+o).startswith('OK')
snaps['after_render']=mem(p.pid)
p.stdin.write('QUIT\n'); p.stdin.flush(); p.wait(timeout=30); err.close()
res={'snapshots_ws_private':{k:[v[0],v[1]] for k,v in snaps.items()},'soak_300_resets_ms':round(soak_ms,1),
     'render_md5':md5(o),'equals_ref':md5(o)==REF}
json.dump(res,open(os.path.join(OUT,'results4.json'),'w',encoding='utf-8'),indent=1)
print(json.dumps(res,indent=1))
