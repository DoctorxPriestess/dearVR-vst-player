#!/bin/bash
# Cold start benchmark: process creation -> engine ready ("params loaded").
#
# Usage: tools/bench/bootbench.sh [runs] [output-file]
#
# The number reported is taken from the app's own diag.log timestamps
# (shell onCreate -> params loaded), so it is immune to adb latency; the wall
# clock figure next to it includes the am start round trip.
set -u
export MSYS_NO_PATHCONV=1
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-adb}
DEV=${DEV:-192.168.101.154:5555}
PKG=com.vstp
ACT=$PKG/com.vstplayer.MainActivity
N=${1:-3}
OUT=${2:-$(dirname "$0")/bootbench.out}
: > "$OUT"

"$ADB" -s "$DEV" connect "$DEV" >/dev/null 2>&1
"$ADB" -s "$DEV" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
"$ADB" -s "$DEV" shell wm dismiss-keyguard >/dev/null 2>&1

for i in $(seq 1 "$N"); do
    "$ADB" -s "$DEV" shell am force-stop $PKG
    sleep 1
    BEFORE=$("$ADB" -s "$DEV" exec-out run-as $PKG wc -l files/diag.log 2>/dev/null | tr -dc '0-9')
    BEFORE=${BEFORE:-0}
    "$ADB" -s "$DEV" shell am start -n $ACT >/dev/null
    T0=$(date +%s%3N)
    READY=""
    for _ in $(seq 1 240); do
        sleep 0.5
        if "$ADB" -s "$DEV" exec-out run-as $PKG cat files/diag.log 2>/dev/null | tail -n +$((BEFORE+1)) | grep -q 'params loaded'; then
            READY=1; break
        fi
    done
    T1=$(date +%s%3N)
    if [ -z "$READY" ]; then
        echo "run $i: TIMEOUT after $(( (T1-T0)/1000 ))s" | tee -a "$OUT"
        continue
    fi
    # Exact elapsed time comes from the diag timestamps (same clock domain).
    LINE=$("$ADB" -s "$DEV" exec-out run-as $PKG cat files/diag.log 2>/dev/null | tail -n +$((BEFORE+1)))
    echo "$LINE" > "/tmp/bb_run$i.log"
    MS=$(echo "$LINE" | awk '
        function ms(t,  a, p) { split(t, a, /[:.]/); return ((a[1]*60)+a[2])*60000 + a[3]*1000 + a[4] }
        /shell onCreate/ { if (start=="") start=ms($1) }
        /params loaded/ { if (start!="") { printf "%.0f", ms($1)-start; exit } }
    ')
    [ -z "$MS" ] && MS="?"
    echo "run $i: ${MS} ms (wall $(( T1-T0 )) ms)" | tee -a "$OUT"
done
