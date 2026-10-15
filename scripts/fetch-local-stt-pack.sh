#!/usr/bin/env bash
# Fetch the moonshine-v2 tiny-en quantized pack (on-device STT models)
# with SHA256 pins from core/models/sherpa-moonshine-v2-tiny.en.manifest.json.
# Binaries are never committed (~44MB); verified here, fail closed.
# Usage: bash scripts/fetch-local-stt-pack.sh [out-dir]
# Default out-dir: /tmp/opencode/mw-moonv2-pack (meter) — copy into the app via
# scripts/meter-local-stt.sh, which pushes it to filesDir.
set -euo pipefail

OUT="${1:-/tmp/opencode/mw-moonv2-pack}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/core/models/sherpa-moonshine-v2-tiny.en.manifest.json"

mkdir -p "$OUT"
python3 - "$MANIFEST" "$OUT" <<'EOF'
import hashlib, json, os, sys, urllib.request
manifest, out = sys.argv[1], sys.argv[2]
with open(manifest) as f:
    m = json.load(f)
base = "https://huggingface.co/csukuangfj2/" + m["pack"] + "/resolve/main"
for name, meta in m["files"].items():
    dest = os.path.join(out, name)
    want = meta["sha256"]
    if os.path.isfile(dest):
        h = hashlib.sha256()
        with open(dest, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        if h.hexdigest() == want:
            print(f"present ok: {name}")
            continue
        print(f"stale hash, re-fetch: {name}")
    url = base + "/" + name
    print(f"fetch: {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "mywhisper-fetch/1"})
    with urllib.request.urlopen(req, timeout=120) as r, open(dest + ".part", "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    os.replace(dest + ".part", dest)
    h = hashlib.sha256()
    with open(dest, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    actual = h.hexdigest()
    if actual != want:
        print(f"SHA256 MISMATCH for {name}:\n  expected {want}\n  actual   {actual}", file=sys.stderr)
        sys.exit(1)
    print(f"ok: {name} sha256={actual} bytes={os.path.getsize(dest)}")
print(f"pack ready: {out}")
EOF
