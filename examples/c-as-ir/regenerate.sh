#!/usr/bin/env bash
# Re-emit generated.user.c (and full out.kira.c snapshot) for each demo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KIRA_BIN="${KIRA:-$ROOT/build/install/kira/bin/kira}"
if [[ ! -x "$KIRA_BIN" ]]; then
  echo "missing kira at $KIRA_BIN -- run ./gradlew installDist" >&2
  exit 1
fi

extract_user() {
  # Everything after language facade (layer 1). Bundle + facade stay in full.c.
  awk 'p; /#endif \/\* KIRA_RUNTIME_H \*\//{p=1}' "$1" | sed '/^$/N;/^\n$/D'
}

for name in hello classes generics collections; do
  dir="$(cd "$(dirname "$0")" && pwd)/$name"
  echo "=== $name ==="
  (
    cd "$dir"
    rm -f out.kira.c app generated.user.c generated.full.c
    "$KIRA_BIN"
    cp out.kira.c generated.full.c
    extract_user out.kira.c > generated.user.c
    cc -std=c17 -O2 -o app out.kira.c
    echo -n "run: "
    ./app | tr '\n' ' '
    echo
    rm -f out.kira.c app
  )
done
echo "done -- commit generated.*.c if the IR changed on purpose"
