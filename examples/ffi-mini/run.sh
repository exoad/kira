#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KIRA_BIN="${KIRA:-$ROOT/build/install/kira/bin/kira}"
cd "$(dirname "$0")"
"$KIRA_BIN"
cc -std=c17 -O2 -o app out.kira.c native/mini_gfx.c
./app
rm -f out.kira.c app
