#!/usr/bin/env bash
# Fetch an on-device LLM (GGUF) model with SHA-256 verification.
#
# Binaries are NEVER committed: this downloads to <dir>/models/ (default:
# <app filesDir> on device via Settings, or ./models for bench).
# SHA pins come from scripts/local-llm-models.json — fill the real
# publisher hash before use (placeholder zeros fail closed).
#
# Usage:
#   scripts/fetch-local-llm.sh --id gemma3-1b-it-q4_0 [--out DIR]
#   scripts/fetch-local-llm.sh --list
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$SCRIPT_DIR/local-llm-models.json"
MODEL_ID=""
OUT_DIR="./models"

while [ $# -gt 0 ]; do
  case "$1" in
    --id) MODEL_ID="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --list) python3 -c "import json; [print(m['id']) for m in json.load(open('$MANIFEST'))['models']]"; exit 0 ;;
    *) echo "usage: $0 --id ID [--out DIR] | --list" >&2; exit 1 ;;
  esac
done

[ -n "$MODEL_ID" ] || { echo "missing --id (see --list)" >&2; exit 1; }

read -r URL SHA SIZE <<<"$(python3 - "$MANIFEST" "$MODEL_ID" <<'EOF'
import json, sys
manifest, want = sys.argv[1], sys.argv[2]
models = json.load(open(manifest))["models"]
m = next((x for x in models if x["id"] == want), None)
if m is None:
    sys.exit(f"unknown model id: {want}")
print(m["url"], m["sha256"], m["size_bytes"])
EOF
)"

if [ "$SHA" = "0000000000000000000000000000000000000000000000000000000000000000" ]; then
  echo "refusing: no real sha256 pinned for '$MODEL_ID' (edit $MANIFEST first)" >&2
  exit 1
fi

mkdir -p "$OUT_DIR/models"
DEST="$OUT_DIR/models/cleanup.gguf"
TMP="$DEST.part"

echo "downloading $MODEL_ID (~$SIZE bytes) -> $DEST"
curl -fL --retry 3 -o "$TMP" "$URL"

echo "$SHA  $TMP" | sha256sum -c -
mv "$TMP" "$DEST"
echo "ok: $DEST ($(stat -c%s "$DEST") bytes, sha verified)"
