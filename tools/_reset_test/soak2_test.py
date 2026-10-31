
import subprocess, os, time, json, ctypes
ROOT = r"D:\AI Misc\vst3_player"; OUT = os.path.join(ROOT, r"tools\_reset_test\out")
PLUGIN = os.path.join(ROOT, r"build_payload\payload\dearVR PRO 2.vst3")
EXE = os.path.join(ROOT, r"juce_renderer\build\vstrender_juce_artefacts\Release\vstrender_juce.exe")
class PMC_EX(ctypes.Structure):
    _fields_=[("cb",ctypes.c_ulong),("PageFaultCount",ctypes.c_ulong),
              ("PeakWorkingSetSize",ctypes.c_size_t),("WorkingSetSize",ctypes.c_size_t),
              ("QuotaPeakPagedPoolUsage",ctypes.c_size_t),("QuotaPagedPoolUsage",ctypes.c_size_t),
              ("QuotaPeakNonPagedPoolUsage",ctypes.c_size_t),("QuotaNonPagedPoolUsage",ctypes.c_size_t),
              ("PagefileUsage",ctypes.c_size_t),("PeakPagefileUsage",ctypes.c_size_t),
              ("PrivateUsage",ctypes.c_size_t)]
def mem(pid):
    h=ctypes.windll.kernel32.OpenProcess(0x1000|0x0400,False,pid)
    c=PMC_EX(); c.cb=ctypes.sizeof(c)
    ctypes.windll.psapi.GetProcessMemoryInfo(h,ctypes.byref(c),ctypes.sizeof(c))
    ctypes.windll.kernel32.CloseHandle(h)
    return int(c.WorkingSetSize), int(c.PrivateUsage)
err=open(os.path.join(OUT,'soak2.err'),'wb')
p=subprocess.Popen([EXE,'-plugin',PLUGIN,'-serve'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=err,text=True,bufsize=1)
assert p.stdout.readline().strip()=='READY'
def cmd(c):
    p.stdin.write(c+'\n'); p.stdin.flush()
    while True:
        l=p.stdout.readline()
        if not l: raise SystemExit('EOF '+c)
        l=l.rstrip('\r\n')
        if not l.startswith('PROG '): return l
snaps={'0':mem(p.pid)}
n=0
for target in (300,600,900,1200):
    while n<target:
        assert cmd('RESET')=='OK'
        n+=1
    snaps[str(target)]=mem(p.pid)
p.stdin.write('QUIT\n'); p.stdin.flush(); p.wait(timeout=30); err.close()
print(json.dumps({k:{'ws_mb':round(v[0]/1048576,2),'priv_mb':round(v[1]/1048576,2)} for k,v in snaps.items()},indent=1))
