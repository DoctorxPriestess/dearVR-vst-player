
import subprocess, os, hashlib, json
ROOT = r"D:\AI Misc\vst3_player"; OUT = os.path.join(ROOT, r"tools\_reset_test\out")
PLUGIN = os.path.join(ROOT, r"build_payload\payload\dearVR PRO 2.vst3")
PRESETS = os.path.join(ROOT, r"build_payload\payload\presets")
A = os.path.join(ROOT, r"tools\bench60.wav")
EXE = os.path.join(ROOT, r"juce_renderer\build\vstrender_juce_artefacts\Release\vstrender_juce.exe")
def md5(p):
    h=hashlib.md5()
    with open(p,'rb') as f:
        for b in iter(lambda: f.read(1<<20), b''): h.update(b)
    return h.hexdigest()
def fresh(preset, out, tag):
    err=open(os.path.join(OUT,tag+'.err'),'wb')
    subprocess.run([EXE,'-plugin',PLUGIN,'-preset',preset,'-in',A,'-out',out],stderr=err,check=True); err.close()
    return md5(out)
def serve_cycle(preset, tag):
    err=open(os.path.join(OUT,tag+'.err'),'wb')
    p=subprocess.Popen([EXE,'-plugin',PLUGIN,'-serve'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=err,text=True,bufsize=1)
    assert p.stdout.readline().strip()=='READY'
    def cmd(c):
        p.stdin.write(c+'\n'); p.stdin.flush()
        while True:
            l=p.stdout.readline()
            if not l: raise SystemExit('EOF')
            l=l.rstrip('\r\n')
            if not l.startswith('PROG '): return l
    assert cmd('PRESET|'+preset)=='OK'
    o1=os.path.join(OUT,tag+'_1.wav'); assert cmd(A+'|'+o1).startswith('OK')
    assert cmd('RESET')=='OK'
    o2=os.path.join(OUT,tag+'_2.wav'); assert cmd(A+'|'+o2).startswith('OK')
    p.stdin.write('QUIT\n'); p.stdin.flush(); p.wait(timeout=20); err.close()
    return md5(o1), md5(o2)
R={}
for name in ['Default.vstpreset','Small Studio.vstpreset','Cathedral.vstpreset','Percussion Plate.vstpreset']:
    pre=os.path.join(PRESETS,name); tag=name.split('.')[0].replace(' ','_')
    r1=fresh(pre, os.path.join(OUT,'x_%s_f1.wav'%tag), 'x_%s_f1'%tag)
    r2=fresh(pre, os.path.join(OUT,'x_%s_f2.wav'%tag), 'x_%s_f2'%tag)
    s1,s2=serve_cycle(pre,'x_%s_cycle'%tag)
    R[name]={'fresh_a':r1,'fresh_b':r2,'fresh_equal':r1==r2,'cycle1':s1,'cycle2':s2,'cycle_equal':s1==s2,
             'cycle1_eq_fresh_a':s1==r1}
json.dump(R,open(os.path.join(OUT,'results5.json'),'w',encoding='utf-8'),indent=1)
print(json.dumps(R,indent=1))
