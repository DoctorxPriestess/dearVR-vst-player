
import subprocess, os, hashlib, sys
ROOT = r"D:\AI Misc\vst3_player"; OUT = os.path.join(ROOT, r"tools\_reset_test\out")
PLUGIN = os.path.join(ROOT, r"build_payload\payload\dearVR PRO 2.vst3")
PRE = os.path.join(ROOT, r"build_payload\payload\presets\Cathedral.vstpreset")
A = os.path.join(ROOT, r"tools\bench60.wav")
NEW = os.path.join(ROOT, r"juce_renderer\build\vstrender_juce_artefacts\Release\vstrender_juce.exe")
OLD = os.path.join(ROOT, r"build_payload\payload\vstrender_juce.exe")
def md5(p):
    h=hashlib.md5()
    with open(p,'rb') as f:
        for b in iter(lambda: f.read(1<<20), b''): h.update(b)
    return h.hexdigest()
def run_serve(exe, cmds, tag):
    err=open(os.path.join(OUT,tag+'.err'),'wb')
    p=subprocess.Popen([exe,'-plugin',PLUGIN,'-serve'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=err,text=True,bufsize=1)
    assert p.stdout.readline().strip()=='READY'
    reps=[]
    for c in cmds:
        p.stdin.write(c+'\n'); p.stdin.flush()
        while True:
            l=p.stdout.readline().strip()
            reps.append(l)
            if not l.startswith('PROG '): break
    p.stdin.write('QUIT\n'); p.stdin.flush(); p.wait(timeout=20); err.close()
    return reps
def run_single(exe, preset, out, tag):
    args=[exe,'-plugin',PLUGIN]+(['-preset',preset] if preset else [])+['-in',A,'-out',out]
    err=open(os.path.join(OUT,tag+'.err'),'wb'); subprocess.run(args,stderr=err,check=True); err.close()

print('old exe md5', md5(OLD))
r=[]
for i in range(2):
    o=os.path.join(OUT,'cmp_old_pre%d.wav'%i); run_single(OLD, PRE, o, 'cmp_old_pre%d'%i); r.append(('old fresh -preset #%d'%i, md5(o)))
for i in range(2):
    o=os.path.join(OUT,'cmp_new_pre%d.wav'%i); run_single(NEW, PRE, o, 'cmp_new_pre%d'%i); r.append(('new fresh -preset #%d'%i, md5(o)))
o=os.path.join(OUT,'cmp_old_serve_pre.wav'); run_serve(OLD, [A+'|'+o+'|'+PRE], 'cmp_old_serve'); r.append(('old serve legacy+preset', md5(o)))
o=os.path.join(OUT,'cmp_new_serve_pre.wav'); run_serve(NEW, [A+'|'+o+'|'+PRE], 'cmp_new_serve'); r.append(('new serve legacy+preset', md5(o)))
o=os.path.join(OUT,'cmp_old_plain.wav'); run_single(OLD, None, o, 'cmp_old_plain'); r.append(('old fresh plain', md5(o)))
o=os.path.join(OUT,'cmp_new_plain.wav'); run_single(NEW, None, o, 'cmp_new_plain'); r.append(('new fresh plain', md5(o)))
for k,v in r: print(k, v)
