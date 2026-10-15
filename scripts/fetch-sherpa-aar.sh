#!/usr/bin/env bash
# Fetch the sherpa-onnx AAR (on-device STT runtime) with SHA256 pin.
# The AAR is never committed (~50MB binary); CI/meter runs this first.
set -euo pipefail

VERSION="${SHERPA_ONNX_VERSION:-1.13.8}"
AAR="sherpa-onnx-${VERSION}.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/${AAR}"
# Pinned 2026-10-15 (sha256sum of the release asset).
PINNED_SHA256="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/libs/$AAR"

if [[ -f "$OUT" ]]; then
  echo "present: $OUT"
else
  echo "fetch: $URL"
  curl -sSL --retry 3 -o "$OUT" "$URL"
fi

ACTUAL="$(sha256sum "$OUT" | cut -d' ' -f1)"
if [[ "$ACTUAL" != "$PINNED_SHA256" ]]; then
  echo "SHA256 MISMATCH for $AAR:" >&2
  echo "  expected $PINNED_SHA256" >&2
  echo "  actual   $ACTUAL" >&2
  exit 1
fi
echo "ok: $AAR sha256=$ACTUAL"
unzip -l "$OUT" | grep -E "jni/(arm64-v8a|x86_64)/" | awk '{print $4}' | sort -u
