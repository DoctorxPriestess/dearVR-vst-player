
import subprocess, os, hashlib, json
ROOT = r"D:\AI Misc\vst3_player"; OUT = os.path.join(ROOT, r"tools\_reset_test\out")
PLUGIN = os.path.join(ROOT, r"build_payload\payload\dearVR PRO 2.vst3")
PRESETS = os.path.join(ROOT, r"build_payload\payload\presets")
A = os.path.join(ROOT, r"tools\bench60.wav")
NEW = os.path.join(ROOT, r"juce_renderer\build\vstrender_juce_artefacts\Release\vstrender_juce.exe")
OLD = os.path.join(ROOT, r"build_payload\payload\vstrender_juce.exe")
def md5(p):
    h=hashlib.md5()
    with open(p,'rb') as f:
        for b in iter(lambda: f.read(1<<20), b''): h.update(b)
    return h.hexdigest()
def run(exe, cmds, tag):
    err=open(os.path.join(OUT,tag+'.err'),'wb')
    p=subprocess.Popen([exe,'-plugin',PLUGIN,'-serve'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=err,text=True,bufsize=1)
    assert p.stdout.readline().strip()=='READY'
    for c in cmds:
        p.stdin.write(c+'\n'); p.stdin.flush()
        while True:
            l=p.stdout.readline()
            if not l: raise SystemExit('EOF '+c)
            if not l.rstrip('\r\n').startswith('PROG '): break
    p.stdin.write('QUIT\n'); p.stdin.flush(); p.wait(timeout=20); err.close()
def fresh(exe, pre, out, tag):
    err=open(os.path.join(OUT,tag+'.err'),'wb')
    subprocess.run([exe,'-plugin',PLUGIN,'-preset',pre,'-in',A,'-out',out],stderr=err,check=True); err.close()
    return md5(out)
R={}
for name in ['Small Studio.vstpreset','Cathedral.vstpreset']:
    pre=os.path.join(PRESETS,name); t=name.split('.')[0].replace(' ','_')
    e={}
    e['old_fresh1']=fresh(OLD,pre,os.path.join(OUT,'y_%s_oldf1.wav'%t),'y_%s_oldf1'%t)
    e['old_fresh2']=fresh(OLD,pre,os.path.join(OUT,'y_%s_oldf2.wav'%t),'y_%s_oldf2'%t)
    o=os.path.join(OUT,'y_%s_oldserve.wav'%t); run(OLD,[A+'|'+o+'|'+pre],'y_%s_oldserve'%t); e['old_serve_legacy_preset']=md5(o)
    o=os.path.join(OUT,'y_%s_oldcmd.wav'%t); run(OLD,['PRESET|'+pre, A+'|'+o],'y_%s_oldcmd'%t); e['old_serve_PRESET_cmd']=md5(o)
    o=os.path.join(OUT,'y_%s_newcmd.wav'%t); run(NEW,['PRESET|'+pre, A+'|'+o],'y_%s_newcmd'%t); e['new_serve_PRESET_cmd']=md5(o)
    e['new_fresh1']=fresh(NEW,pre,os.path.join(OUT,'y_%s_newf1.wav'%t),'y_%s_newf1'%t)
    R[name]=e
json.dump(R,open(os.path.join(OUT,'results6.json'),'w',encoding='utf-8'),indent=1)
print(json.dumps(R,indent=1))
