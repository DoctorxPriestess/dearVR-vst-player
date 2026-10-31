#!/bin/bash
# Host-side checks for the parts of core/ that do not depend on Android.
#
# Compiles the dependency-free core classes together with tools/tests and runs them on the
# desktop JDK. Anything that needs android.* cannot be checked here and is covered by the
# compile gate + the on-device render regression instead.
#
# Usage: tools/tests/run.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
# javac/java are native Windows binaries here, so hand them a mixed path (D:/...) instead of
# the MSYS one (/d/...) — MSYS_NO_PATHCONV=1 (set by build.sh) disables the automatic rewrite.
if command -v cygpath >/dev/null 2>&1; then ROOT=$(cygpath -m "$ROOT"); fi
SRC="$ROOT/android/app/src/main/java/com/vstplayer/core"
OUT="$ROOT/tools/tests/build"

rm -rf "$OUT"
mkdir -p "$OUT"

# Keep this list to classes whose imports are java.* only.
CLASSES=(Io Paths Segment Param Loudness Wav)

SOURCES=()
for c in "${CLASSES[@]}"; do SOURCES+=("$SRC/$c.java"); done
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "$ROOT/tools/tests/com" -name '*.java')

javac -encoding UTF-8 -d "$OUT" "${SOURCES[@]}"
java -cp "$OUT" com.vstplayer.tests.SelfCheck
