#!/usr/bin/env bash
set -u
export MSYS_NO_PATHCONV=1
A="adb -s 192.168.101.154:5555"
D="/sdcard/我的文件"
E1=6332550932; E2=2285141010; E3=1471137234
for i in $(seq 1 36); do
  out=$($A shell "stat -c %s '$D/1.zip' 2>/dev/null; stat -c %s '$D/播放列表.zip' 2>/dev/null; stat -c %s '$D/钢琴曲.zip' 2>/dev/null" | tr -d '\r')
  a=$(echo "$out" | sed -n 1p); b=$(echo "$out" | sed -n 2p); c=$(echo "$out" | sed -n 3p)
  a=${a:-0}; b=${b:-0}; c=${c:-0}
  printf "%s  1.zip %6.2f/%6.2f GB  播放列表 %5.2f/%5.2f GB  钢琴曲 %5.2f/%5.2f GB\n" \
    "$(date +%H:%M:%S)" "$(echo "scale=2;$a/1073741824" | bc)" "$(echo "scale=2;$E1/1073741824" | bc)" \
    "$(echo "scale=2;$b/1073741824" | bc)" "$(echo "scale=2;$E2/1073741824" | bc)" \
    "$(echo "scale=2;$c/1073741824" | bc)" "$(echo "scale=2;$E3/1073741824" | bc)"
  if [ "$a" = "$E1" ] && [ "$b" = "$E2" ] && [ "$c" = "$E3" ]; then echo "ALL DONE"; break; fi
  sleep 10
done
