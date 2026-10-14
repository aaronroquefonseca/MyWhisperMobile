#!/usr/bin/env bash
# Fill core/models/<pack>.manifest.json with sha256 + sizes from a local
# pack dir (downloaded separately, never committed). Usage:
#   bash scripts/pin-model-manifest.sh /tmp/opencode/pack
# Copies PACK_MANIFEST pins into core/src/local_stt.rs afterwards by hand
# (single source of truth stays the JSON; the Rust table mirrors it).
set -euo pipefail

PACK_DIR="${1:?usage: pin-model-manifest.sh <pack-dir>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/core/models/sherpa-tiny.en.manifest.json"

python3 - "$PACK_DIR" "$MANIFEST" <<'EOF'
import hashlib, json, os, sys
pack, manifest = sys.argv[1], sys.argv[2]
with open(manifest) as f:
    m = json.load(f)
for name, meta in m["files"].items():
    p = os.path.join(pack, name)
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    meta["sha256"] = h.hexdigest()
    meta["bytes"] = os.path.getsize(p)
    print(f"{name} sha256={meta['sha256']} bytes={meta['bytes']}")
with open(manifest, "w") as f:
    json.dump(m, f, indent=2)
    f.write("\n")
print(f"wrote {manifest}")
EOF
