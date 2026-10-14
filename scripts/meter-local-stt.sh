#!/usr/bin/env bash
# Meter on-device STT on the emulator (short hold, release ASAP).
# Usage: bash scripts/meter-local-stt.sh <pack-dir> <wav-dir> [refs.tsv] [lang]
#   pack-dir: tiny.en-encoder.int8.onnx + tiny.en-decoder.int8.onnx + tiny.en-tokens.txt
#   wav-dir:  16-bit mono .wav clips (10s/30s + LibriSpeech subset)
#   refs.tsv: optional "<clip-name>\t<reference>" for host-side WER
# Prints the numbers table the gate needs:
#   load ms | RTF per clip | peak PSS | mAh | WER
set -euo pipefail

PACK_DIR="${1:?usage: meter-local-stt.sh <pack-dir> <wav-dir> [refs.tsv] [lang]}"
WAV_DIR="${2:?usage: meter-local-stt.sh <pack-dir> <wav-dir> [refs.tsv] [lang]}"
REFS="${3:-}"
LANG="${4:-en}"
APP="com.mywhisper.mobile"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
adb wait-for-device
echo "== build debug apk =="
(
  cd "$ROOT/android"
  ./gradlew :app:assembleDebug -x test --console=plain -q
)
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
adb install -r "$APK" >/dev/null

echo "== push pack + wavs =="
adb push "$PACK_DIR/tiny.en-encoder.int8.onnx" /data/local/tmp/mw-enc.onnx >/dev/null
adb push "$PACK_DIR/tiny.en-decoder.int8.onnx" /data/local/tmp/mw-dec.onnx >/dev/null
adb push "$PACK_DIR/tiny.en-tokens.txt" /data/local/tmp/mw-tok.txt >/dev/null
# /data/local/tmp is shell-owned: make readable, then copy via run-as
# with the ABSOLUTE data dir (run-as cwd is not the data dir).
adb shell "chmod 644 /data/local/tmp/mw-enc.onnx /data/local/tmp/mw-dec.onnx /data/local/tmp/mw-tok.txt /data/local/tmp/mw-clip.wav 2>/dev/null; true"
DATA_DIR="$(adb shell "run-as $APP pwd" | tr -d '\r')"
adb shell "run-as $APP mkdir -p $DATA_DIR/files/models/sherpa-tiny.en $DATA_DIR/files/meter"
adb shell "run-as $APP cp /data/local/tmp/mw-enc.onnx $DATA_DIR/files/models/sherpa-tiny.en/tiny.en-encoder.int8.onnx"
adb shell "run-as $APP cp /data/local/tmp/mw-dec.onnx $DATA_DIR/files/models/sherpa-tiny.en/tiny.en-decoder.int8.onnx"
adb shell "run-as $APP cp /data/local/tmp/mw-tok.txt $DATA_DIR/files/models/sherpa-tiny.en/tiny.en-tokens.txt"

WAV_LIST=""
for w in "$WAV_DIR"/*.wav; do
  base="$(basename "$w")"
  adb push "$w" /data/local/tmp/mw-clip.wav >/dev/null
  adb shell "chmod 644 /data/local/tmp/mw-clip.wav"
  adb shell "run-as $APP cp /data/local/tmp/mw-clip.wav $DATA_DIR/files/meter/$base"
  if [[ -z "$WAV_LIST" ]]; then
    WAV_LIST="$DATA_DIR/files/meter/$base"
  else
    WAV_LIST="$WAV_LIST,$DATA_DIR/files/meter/$base"
  fi
done
echo "clips: $(ls "$WAV_DIR"/*.wav | wc -l)"

adb logcat -c
adb shell dumpsys batterystats --reset >/dev/null 2>&1 || true
PID="$(adb shell pidof "$APP" | tr -d '\r')"
echo "== run meter activity (prev pid '${PID:-none}', polling peak RSS) =="
adb shell am start -n "$APP/.LocalSttMeterActivity" --es wavs "$WAV_LIST" --es lang "$LANG" >/dev/null

PEAK_KB=0
for i in $(seq 1 60); do
  sleep 5
  PID="$(adb shell pidof "$APP" | tr -d '\r' | head -1)"
  if [[ -n "$PID" ]]; then
    HWM="$(adb shell "cat /proc/$PID/status 2>/dev/null | grep VmHWM" | grep -oE "[0-9]+" | head -1)"
    if [[ -n "$HWM" && "$HWM" -gt "$PEAK_KB" ]]; then
      PEAK_KB="$HWM"
    fi
  fi
  if adb logcat -d | grep -q "MW_METER.*done clips="; then
    break
  fi
done
echo "peakVmHWM=${PEAK_KB}kB ($((PEAK_KB / 1024))MB)"
echo "== MW_METER =="
adb logcat -d | grep "MW_METER" | sed 's/.*MW_METER/MW_METER/'

echo "== meminfo =="
adb shell dumpsys meminfo "$APP" | grep -E "TOTAL|Native Heap" | head -5

echo "== batterystats (uid charge since reset) =="
adb shell dumpsys batterystats "$APP" | grep -iE "uId.*mAh|computed drain" | head -5 || true

if [[ -n "$REFS" ]]; then
  echo "== WER =="
  adb logcat -d | grep -E "MW_METER (clip=|cliptext=)" > /tmp/mw-meter-clips.log || true
  python3 - "$REFS" /tmp/mw-meter-clips.log <<'EOF'
import re, sys
refs = {}
with open(sys.argv[1]) as f:
    for line in f:
        line = line.rstrip("\n")
        if "\t" in line:
            k, v = line.split("\t", 1)
            refs[k.strip()] = v.strip()
parts = {}
for line in open(sys.argv[2]):
    m = re.search(r"cliptext=(\S+) part=(\d+) text=(.*)$", line)
    if m:
        parts.setdefault(m.group(1), {})[int(m.group(2))] = m.group(3)
hyps = {k: "".join(v[i] for i in sorted(v)) for k, v in parts.items()}
def norm(s):
    return re.sub(r"[^a-z0-9' ]", "", s.lower()).split()
tot_ed = tot_n = scored = 0
for k, ref in refs.items():
    if k not in hyps:
        print(f"missing hyp: {k}")
        continue
    r, h = norm(ref), norm(hyps[k])
    import functools
    @functools.lru_cache(maxsize=None)
    def dp(i, j, r=tuple(r), h=tuple(h)):
        if i == 0:
            return j
        if j == 0:
            return i
        return min(dp(i-1, j)+1, dp(i, j-1)+1, dp(i-1, j-1)+(r[i-1] != h[j-1]))
    e = dp(len(r), len(h))
    tot_ed += e
    tot_n += len(r)
    scored += 1
    print(f"{k}: ref={len(r)}w ed={e}")
wer = (tot_ed / tot_n * 100) if tot_n else float("nan")
print(f"WER {wer:.2f}% ({tot_ed} err / {tot_n} ref words, {scored} clips)")
EOF
fi
echo "done."
