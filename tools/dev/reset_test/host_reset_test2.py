
import subprocess, os, hashlib, time, json, wave
import numpy as np
ROOT = r"D:\AI Misc\vst3_player"; OUT = os.path.join(ROOT, r"tools\_reset_test\out")
PLUGIN = os.path.join(ROOT, r"build_payload\payload\dearVR PRO 2.vst3")
PRE = os.path.join(ROOT, r"build_payload\payload\presets\Cathedral.vstpreset")
A = os.path.join(ROOT, r"tools\bench60.wav")
EXE = os.path.join(ROOT, r"juce_renderer\build\vstrender_juce_artefacts\Release\vstrender_juce.exe")
def md5(p):
    h=hashlib.md5()
    with open(p,'rb') as f:
        for b in iter(lambda: f.read(1<<20), b''): h.update(b)
    return h.hexdigest()
class Serve:
    def __init__(self, extra=(), tag="s"):
        self.err=open(os.path.join(OUT,tag+'.err'),'wb'); self.t0=time.perf_counter()
        self.p=subprocess.Popen([EXE,'-plugin',PLUGIN]+list(extra)+['-serve'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.err,text=True,bufsize=1)
        assert self.p.stdout.readline().strip()=='READY'; self.ready_ms=(time.perf_counter()-self.t0)*1000
    def cmd(self,c):
        self.p.stdin.write(c+'\n'); self.p.stdin.flush(); out=[]
        while True:
            l=self.p.stdout.readline()
            if not l: raise SystemExit('EOF on '+c)
            l=l.rstrip('\r\n'); out.append(l)
            if not l.startswith('PROG '): return out
    def timeit(self,c,n=11):
        ts=[]
        for _ in range(n):
            t0=time.perf_counter(); r=self.cmd(c)[-1]; ts.append((time.perf_counter()-t0)*1000)
            assert r.startswith('OK'), r
        ts.sort(); return ts
    def close(self):
        self.p.stdin.write('QUIT\n'); self.p.stdin.flush(); self.p.wait(timeout=20); self.err.close()
def med(v): return v[len(v)//2]
R={}

# 1) preset-loaded audio determinism inside one process around RESET
def preset_cycle(mode, tag):
    s=Serve(tag=tag)
    assert s.cmd('PRESET|'+PRE)[-1]=='OK'
    o1=os.path.join(OUT,tag+'_1.wav'); s.cmd(A+'|'+o1)
    rep=s.cmd('RESET'+(('|'+mode) if mode else ''))[-1]
    o2=os.path.join(OUT,tag+'_2.wav'); s.cmd(A+'|'+o2)
    s.close()
    return {'reset_reply':rep,'md5_1':md5(o1),'md5_2':md5(o2),'equal':md5(o1)==md5(o2)}
R['preset_cycle_soft']=preset_cycle('soft','p_soft')
R['preset_cycle_hard']=preset_cycle('hard','p_hard')
# reference: 3 fresh processes with -preset
fresh=[]
for i in range(3):
    o=os.path.join(OUT,'p_fresh%d.wav'%i)
    err=open(os.path.join(OUT,'p_fresh%d.err'%i),'wb')
    subprocess.run([EXE,'-plugin',PLUGIN,'-preset',PRE,'-in',A,'-out',o],stderr=err,check=True); err.close()
    fresh.append(md5(o))
R['preset_fresh_processes']={'md5s':fresh,'all_equal':len(set(fresh))==1}

# 2) timings: 11 reps
s=Serve(tag='t2')
R['reset_soft_ms']=s.timeit('RESET|soft')
R['reset_hard_ms']=s.timeit('RESET|hard')
R['reset_default_ms']=s.timeit('RESET')
s.cmd('PRESET|'+PRE)
R['reset_hard_with_preset_ms']=s.timeit('RESET|hard')
s.close()
cold=[]
for i in range(7):
    t0=time.perf_counter(); s=Serve(tag='coldx%d'%i); cold.append((time.perf_counter()-t0)*1000); s.close()
R['cold_start_ms']=sorted(cold)
R['timing_summary']={
 'soft_median':round(med(R['reset_soft_ms']),1),'soft_min':round(R['reset_soft_ms'][0],1),
 'hard_median':round(med(R['reset_hard_ms']),1),'hard_min':round(R['reset_hard_ms'][0],1),
 'default_median':round(med(R['reset_default_ms']),1),
 'hard_with_preset_median':round(med(R['reset_hard_with_preset_ms']),1),
 'cold_median':round(med(R['cold_start_ms']),1),'cold_min':round(cold[0],1)}

# 3) robustness: failed render then RESET then render
s=Serve(tag='edge')
bad=s.cmd(os.path.join(OUT,'no_such_file.wav')+'|'+os.path.join(OUT,'x.wav'))[-1]
rr=s.cmd('RESET')[-1]
o=os.path.join(OUT,'edge_render.wav'); ok=s.cmd(A+'|'+o)[-1]
s.close()
R['edge_failed_render_then_reset']={'bad_reply':bad,'reset_reply':rr,'render_reply':ok,'md5':md5(o),'equals_ref':md5(o)=='d5c1b19add38445d773609691f49aeae'}

with open(os.path.join(OUT,'results2.json'),'w',encoding='utf-8') as f: json.dump(R,f,indent=1,ensure_ascii=False)
print(json.dumps(R,indent=1,ensure_ascii=False))
