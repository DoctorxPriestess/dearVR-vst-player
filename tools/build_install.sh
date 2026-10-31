#!/usr/bin/env bash
# Deprecated alias. Kept because older notes and benchmark logs call it.
# Use tools/build.sh install [flavor] instead.
exec "$(cd "$(dirname "$0")" && pwd)/build.sh" install "$@"
