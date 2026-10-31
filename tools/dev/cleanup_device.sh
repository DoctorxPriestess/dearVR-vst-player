#!/usr/bin/env bash
set -u
export MSYS_NO_PATHCONV=1
ADB="adb -s 192.0.2.10:5555"

echo "=== before ==="
$ADB shell "run-as com.vstp sh -c 'ls -a files/tmp 2>/dev/null | tr \"\\n\" \" \"; echo; ls -l files/glibc/opt/vstenv/work/in files/glibc/opt/vstenv/work/out 2>&1 | head -12; ls -d files/glibc/opt/vstenv/work/seg 2>&1; ls files/boot.conf files/rab.sh 2>&1'"
$ADB shell "ls -l /data/local/tmp | head -20"

echo "=== clean device app files (A.5 / A.7.2) ==="
$ADB shell "run-as com.vstp sh -c 'rm -f files/tmp/probe.sh files/tmp/t.sh files/tmp/probe_E0.out files/tmp/probe_E1.out files/tmp/probe_E2.out files/rab.sh files/glibc/opt/vstenv/work/cmd.txt files/glibc/opt/vstenv/work/in/bench60.wav files/glibc/opt/vstenv/work/out/ab.wav'"
echo "=== clean /data/local/tmp ==="
$ADB shell "rm -f /data/local/tmp/bench60.wav /data/local/tmp/cmd.txt /data/local/tmp/rab.sh /data/local/tmp/_cpu.sh /data/local/tmp/Classical.vstpreset /data/local/tmp/HD2.vstpreset /data/local/tmp/Piano_solo.vstpreset /data/local/tmp/Pop.vstpreset"

echo "=== after ==="
$ADB shell "run-as com.vstp sh -c 'ls -a files/tmp | tr \"\\n\" \" \"; echo; ls -l files/glibc/opt/vstenv/work/in files/glibc/opt/vstenv/work/out 2>&1 | head -12; ls -d files/.ready-* 2>&1; ls -l files/glibc/opt/vstenv/presets | tail -6'"
$ADB shell "ls -l /data/local/tmp | head -12"
