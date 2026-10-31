#!/system/bin/sh
# 设备侧：按掩码跑一次 60s 渲染（引擎启动 + 渲染），由宿主计时
BIN=/data/user/0/com.vstp/files/bin
GLIBC=/data/user/0/com.vstp/files/glibc
TMPD=/data/user/0/com.vstp/files/tmp
MASK="$1"
PREFIX=""
[ -n "$MASK" ] && PREFIX="/system/bin/taskset $MASK "
cd $BIN || exit 1
export PROOT_LOADER=$BIN/proot-loader PROOT_TMP_DIR=$TMPD LD_LIBRARY_PATH=$BIN HOME=/root TERM=dumb
rm -f $GLIBC/opt/vstenv/work/out/ab.wav
$PREFIX ./proot -r $GLIBC -0 -b /dev -b /proc -b /sys -b $TMPD:/tmp -b $TMPD:/data/data/com.termux/files/usr/tmp -w / /lib/ld-linux-aarch64.so.1 --library-path /lib:/usr/lib /bin/bash /opt/vstenv/run.sh < $GLIBC/opt/vstenv/work/cmd.txt
echo "guest_exit=$?"
ls -l $GLIBC/opt/vstenv/work/out/ab.wav 2>/dev/null | awk '{print "out=" $5 "B"}'
