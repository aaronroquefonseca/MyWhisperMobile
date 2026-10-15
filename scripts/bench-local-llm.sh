#!/usr/bin/env bash
# Owner on-device bench for the chosen provider (MediaPipe `.task`).
#
# The MediaPipe LLM Inference API targets physical GPUs (Pixel 8 / S23+)
# and does not reliably support emulators — run on hardware.
# Records: model id/size, load latency, TTFT, tok/s, RAM (dumpsys), APK delta.
#
# Usage (device with the model pushed to /data/local/tmp/llm/):
#   scripts/bench-local-llm.sh --model cleanup.task --prompt "fix: hello world"
set -euo pipefail

MODEL="cleanup.task"
PROMPT="Clean this dictation: hello world this is a test period new line total three hundred dollars"
while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --prompt) PROMPT="$2"; shift 2 ;;
    *) echo "usage: $0 [--model FILE] [--prompt TEXT]" >&2; exit 1 ;;
  esac
done

DEVICE_MODEL="$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
echo "== device: ${DEVICE_MODEL:-unknown} =="
echo "== model: $MODEL ($(adb shell stat -c%s "/data/local/tmp/llm/$MODEL" 2>/dev/null | tr -d '\r') bytes) =="
echo "== prompt chars: ${#PROMPT} =="
echo
echo "Run the in-app bench (Settings > Local LLM > Benchmark) with:"
echo "  prompt: $PROMPT"
echo
echo "Then paste below: load_ms, ttft_ms, decode_tok_s, ram_mb."
echo "Reference (literature, NOT this device):"
echo "  MediaPipe Gemma-2B int4 GPU: prefill 100s tok/s, decode ~10-20 tok/s (Pixel 8/S23, Google blog)"
echo "  llama.cpp Llama-3.2-1B Q4 CPU: decode ~8-15 tok/s (ARM NEON, mid phones)"
echo "  MLC Qwen2.5-1.5B q4f16: ~11 tok/s measured (TinyBench, S24 Ultra)"
echo
echo "-- dumpsys meminfo snapshot (run while generating) --"
adb shell dumpsys meminfo com.mywhisper.mobile 2>/dev/null | head -20 || echo "(app not running)"
