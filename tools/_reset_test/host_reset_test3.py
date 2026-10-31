
import subprocess, os, hashlib, time, json, statistics
ROOT = r"D:\AI Misc\vst3_player"; OUT = os.path.join(ROOT, r"tools\_reset_test\out")
PLUGIN = os.path.join(ROOT, r"build_payload\payload\dearVR PRO 2.vst3")
A = os.path.join(ROOT, r"tools\bench60.wav")
EXE = os.path.join(ROOT, r"juce_renderer\build\vstrender_juce_artefacts\Release\vstrender_juce.exe")
REF = 'd5c1b19add38445d773609691f49aeae'
def md5(p):
    h=hashlib.md5()
    with open(p,'rb') as f:
        for b in iter(lambda: f.read(1<<20), b''): h.update(b)
    return h.hexdigest()
class Serve:
    def __init__(self, tag):
        self.err=open(os.path.join(OUT,tag+'.err'),'wb'); self.t0=time.perf_counter()
        self.p=subprocess.Popen([EXE,'-plugin',PLUGIN,'-serve'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.err,text=True,bufsize=1)
        assert self.p.stdout.readline().strip()=='READY'
    def cmd(self,c):
        self.p.stdin.write(c+'\n'); self.p.stdin.flush(); out=[]
        while True:
            l=self.p.stdout.readline()
            if not l: raise SystemExit('EOF '+c)
            l=l.rstrip('\r\n'); out.append(l)
            if not l.startswith('PROG '): return out
    def close(self):
        self.p.stdin.write('QUIT\n'); self.p.stdin.flush(); self.p.wait(timeout=20); self.err.close()
R={}
# RESET on a fresh instance that never rendered
s=Serve('t3'); assert s.cmd('RESET')[-1]=='OK'
o=os.path.join(OUT,'t3_reset_first.wav'); assert s.cmd(A+'|'+o)[-1].startswith('OK')
R['reset_before_first_render']={'md5':md5(o),'equals_ref':md5(o)==REF}
# interleaved timing, 21 reps each
N=21; ts={'RESET|soft':[],'RESET|hard':[],'RESET':[]}
for i in range(N):
    for c in ts:
        t0=time.perf_counter(); r=s.cmd(c)[-1]; ts[c].append((time.perf_counter()-t0)*1000); assert r=='OK',r
s.close()
R['timing']={k:{'median_ms':round(statistics.median(v),2),'min_ms':round(min(v),2),'max_ms':round(max(v),2),'n':len(v)} for k,v in ts.items()}
cold=[]
for i in range(9):
    t0=time.perf_counter(); s=Serve('coldz%d'%i); cold.append((time.perf_counter()-t0)*1000); s.close()
R['cold_start']={'median_ms':round(statistics.median(cold),2),'min_ms':round(min(cold),2),'max_ms':round(max(cold),2),'n':len(cold)}
# render timing for context (60s file)
s=Serve('t4')
t0=time.perf_counter(); s.cmd(A+'|'+os.path.join(OUT,'t4.wav')); R['render_60s_ms']=round((time.perf_counter()-t0)*1000,1)
s.close()
json.dump(R,open(os.path.join(OUT,'results3.json'),'w',encoding='utf-8'),indent=1,ensure_ascii=False)
print(json.dumps(R,indent=1,ensure_ascii=False))
