
import subprocess, os, hashlib, json
import numpy as np, wave
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
def load(p):
    w=wave.open(p,'rb'); n,ch,sw,sr=w.getnframes(),w.getnchannels(),w.getsampwidth(),w.getframerate()
    raw=w.readframes(n); w.close()
    a=np.frombuffer(raw,dtype=np.uint8).reshape(-1,3).astype(np.int32)
    v=(a[:,0]|(a[:,1]<<8)|(a[:,2]<<16)); v=np.where(v>=1<<23,v-(1<<24),v).astype(np.float64)/(1<<23)
    return v.reshape(-1,ch)
def stats(pa,pb):
    a,b=load(pa),load(pb); d=a-b
    rms=float(np.sqrt(np.mean(d*d))); mx=float(np.max(np.abs(d)))
    db=lambda x: -999.0 if x<=0 else round(20*np.log10(x),2)
    return {'md5_equal':md5(pa)==md5(pb),'rms_db':db(rms),'max_db':db(mx)}
def run_serve(cmds, tag):
    err=open(os.path.join(OUT,tag+'.err'),'wb')
    p=subprocess.Popen([EXE,'-plugin',PLUGIN,'-serve'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=err,text=True,bufsize=1)
    assert p.stdout.readline().strip()=='READY'
    for c in cmds:
        p.stdin.write(c+'\n'); p.stdin.flush()
        while True:
            l=p.stdout.readline()
            if not l: raise SystemExit('EOF')
            if not l.rstrip('\r\n').startswith('PROG '): break
    p.stdin.write('QUIT\n'); p.stdin.flush(); p.wait(timeout=20); err.close()
# baseline with preset, no RESET, same process
o1=os.path.join(OUT,'pbase1.wav'); o2=os.path.join(OUT,'pbase2.wav')
run_serve(['PRESET|'+PRE, A+'|'+o1, A+'|'+o2], 'pbase')
# RESET hard cycle with preset (fresh run)
o3=os.path.join(OUT,'phard1.wav'); o4=os.path.join(OUT,'phard2.wav')
run_serve(['PRESET|'+PRE, A+'|'+o3, 'RESET', A+'|'+o4], 'phard')
R={'preset_no_reset':'%s'%stats(o1,o2),'preset_after_hard_reset':'%s'%stats(o3,o4),
   'md5':{'base1':md5(o1),'base2':md5(o2),'reset1':md5(o3),'reset2':md5(o4)}}
print(json.dumps(R,indent=1))
