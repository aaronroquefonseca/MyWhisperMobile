//! On-device STT pack plumbing (moonshine v2 tiny-en quantized).
//!
//! The actual inference lives on the Kotlin side via the sherpa-onnx
//! AAR (`LocalStt.kt`, `OfflineRecognizer` + `OfflineMoonshineModelConfig`
//! with `mergedDecoder`). This module owns the shared contract: pack
//! layout under `filesDir`, the pinned SHA256 manifest, the
//! `try_transcribe` entry the engine calls with `filesDir` plumbed
//! through, plus the host-testable pieces of the decode strategy:
//! VAD chunking (works around the merged-decoder ORT broadcast bug
//! that breaks WER past ~9s of continuous audio) and the WER scorer
//! with the ship gates.
//!
//! Rust has no ONNX runtime linked, so `try_transcribe` verifies the
//! pack and returns `Err` when inference must happen elsewhere
//! (Kotlin) or the pack is absent — the server chain fallback stays
//! intact. The meter harness + `LocalStt.kt` do the real decode, using
//! the same chunk constants below (mirrored in `LocalStt.chunkPcm`).

use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};

/// Model pack dir relative to `filesDir`.
pub const PACK_REL_DIR: &str = "models/sherpa-moonshine-v2-tiny.en";

/// Expected pack files (moonshine v2 tiny-en quantized: encoder +
/// merged decoder + tokens).
pub const PACK_FILES: &[&str] = &[
    "encoder_model.ort",
    "decoder_model_merged.ort",
    "tokens.txt",
];

/// Pinned SHA256 manifest (mirrors
/// `core/models/sherpa-moonshine-v2-tiny.en.manifest.json`, filled by
/// `scripts/pin-model-manifest.sh`). verify fails closed on mismatch.
pub const PACK_MANIFEST: &[(&str, &str)] = &[
    (
        "encoder_model.ort",
        "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221",
    ),
    (
        "decoder_model_merged.ort",
        "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0",
    ),
    (
        "tokens.txt",
        "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049",
    ),
];

// -- ship gates (emulator-measured numbers go in the PR body) --------

/// Max end-to-end decode cost per audio second (decode wall / audio).
pub const RTF_GATE: f32 = 1.0;
/// Max steady-state process peak (VmHWM / meminfo TOTAL) in MiB.
pub const PSS_MB_GATE: f32 = 400.0;
/// Max word error rate on the meter clip set, in percent.
pub const WER_PCT_GATE: f32 = 20.0;

// -- VAD chunking -------------------------------------------------------
// Workaround for the sherpa-onnx 1.13.8 merged-decoder ORT broadcast
// bug: a single decode past ~9s of continuous audio degrades (WER
// fails), so long utterances are cut on silence and decoded per
// chunk, then joined with spaces. `LocalStt.chunkPcm` mirrors these
// constants; keep them in sync by hand.

/// Hard cap per decode chunk: under the ~9s failure point.
pub const MAX_CHUNK_SECS: f32 = 8.0;
/// Chunks shorter than this merge forward (avoids 1-word decodes).
pub const MIN_CHUNK_SECS: f32 = 1.0;
/// Silence run that opens a cut point.
pub const SILENCE_CUT_SECS: f32 = 0.3;
/// VAD frame length (20 ms at 16 kHz).
pub const VAD_FRAME_SAMPLES: usize = 320;
/// Frame RMS (full-scale-normalized) at/above this = speech.
pub const VAD_RMS_THRESHOLD: f32 = 0.02;

/// Pack dir for a given `filesDir`.
pub fn pack_dir(files_dir: &Path) -> PathBuf {
    files_dir.join(PACK_REL_DIR)
}

/// Verify pack presence (+ SHA256 when pinned). `Ok(())` = usable.
pub fn verify_pack(files_dir: &Path) -> Result<(), String> {
    let dir = pack_dir(files_dir);
    if !dir.is_dir() {
        return Err(format!("local pack missing: {}", dir.display()));
    }
    for (name, pinned) in PACK_MANIFEST {
        let p = dir.join(name);
        let bytes = std::fs::read(&p).map_err(|e| format!("local pack read {name}: {e}"))?;
        if bytes.is_empty() {
            return Err(format!("local pack empty: {name}"));
        }
        if !pinned.is_empty() {
            let digest = format!("{:x}", Sha256::digest(&bytes));
            if digest != *pinned {
                return Err(format!("local pack sha mismatch: {name}"));
            }
        }
    }
    Ok(())
}

/// Engine entry: `filesDir` plumbed through. Returns `Err` when the
/// pack is absent/invalid (caller falls back to the server chain) —
/// Rust never runs inference here; Kotlin `LocalStt` does.
pub fn try_transcribe(files_dir: &Path, _pcm16: &[i16]) -> Result<String, String> {
    verify_pack(files_dir)?;
    Err("local inference lives in LocalStt.kt (sherpa-onnx AAR); rust try_transcribe verifies pack only".to_string())
}

/// Split 16 kHz mono PCM into decode chunks, returned as
/// `(start, end)` sample ranges. Cut on silence runs >=
/// `SILENCE_CUT_SECS` once the chunk holds >= `MIN_CHUNK_SECS`;
/// force-cut at `MAX_CHUNK_SECS`. All-silence input (or empty)
/// yields no chunks — the caller treats that as a miss and keeps
/// the server fallback.
pub fn chunk_pcm16(pcm: &[i16], sample_rate: u32) -> Vec<(usize, usize)> {
    if pcm.is_empty() || sample_rate == 0 {
        return Vec::new();
    }
    let frame = (sample_rate as usize / 50).max(1);
    let max_len = (MAX_CHUNK_SECS * sample_rate as f32) as usize;
    let min_len = (MIN_CHUNK_SECS * sample_rate as f32) as usize;
    let cut_frames = ((SILENCE_CUT_SECS * sample_rate as f32) as usize / frame).max(1);

    // Per-frame speech flags.
    let n_frames = pcm.len().div_ceil(frame);
    let mut speech = Vec::with_capacity(n_frames);
    for f in 0..n_frames {
        let s = f * frame;
        let e = (s + frame).min(pcm.len());
        let mut sum = 0f64;
        for &v in &pcm[s..e] {
            let x = v as f64 / 32768.0;
            sum += x * x;
        }
        let rms = (sum / (e - s).max(1) as f64).sqrt() as f32;
        speech.push(rms >= VAD_RMS_THRESHOLD);
    }

    let mut chunks = Vec::new();
    let mut start = 0usize; // chunk start (samples)
    let mut silence_run = 0usize; // trailing silent frames in chunk
    let mut has_speech = false;
    let mut f = 0usize;
    // Cut offset inside the trailing silence: cut at its start so the
    // next chunk doesn't inherit a long leading silence.
    while f < n_frames {
        let chunk_len = f * frame - start;
        if speech[f] {
            has_speech = true;
            silence_run = 0;
        } else {
            silence_run += 1;
        }
        let frame_end = ((f + 1) * frame).min(pcm.len());
        let at_max = chunk_len + frame >= max_len && frame_end - start >= min_len;
        let at_cut =
            silence_run >= cut_frames && (f + 1) * frame - start >= min_len + silence_run * frame;
        if at_max || (at_cut && has_speech) {
            // Back the cut up to the silence-run start.
            let cut = if at_cut && !at_max {
                frame_end
                    .saturating_sub(silence_run * frame)
                    .max(start + min_len)
            } else {
                frame_end
            };
            if has_speech && cut > start {
                chunks.push((start, cut.min(pcm.len())));
            }
            start = cut.min(pcm.len());
            has_speech = false;
            silence_run = 0;
        }
        f += 1;
    }
    // Tail: keep when it holds speech and isn't a sliver already
    // covered; merge sub-min tails (drop when < ~0.25s of speech-less
    // audio — decoder noise, server fallback covers real misses).
    if start < pcm.len() {
        let tail_speech = speech.iter().skip(start.div_ceil(frame)).any(|&s| s);
        if tail_speech || has_speech {
            if let Some(last) = chunks.last_mut() {
                if pcm.len() - last.0 < min_len {
                    // Tiny overall: single chunk.
                    last.1 = pcm.len();
                } else if pcm.len() - start < min_len / 4 {
                    // Sliver tail: extend the previous chunk over it.
                    last.1 = pcm.len();
                } else {
                    chunks.push((start, pcm.len()));
                }
            } else {
                chunks.push((start, pcm.len()));
            }
        }
    }
    // Degenerate: one silent blob with no cuts and no speech anywhere
    // already yields nothing; a fully-speech long input was force-cut
    // above. If a single speech chunk somehow exceeds the cap (rounding),
    // split it hard.
    let mut out = Vec::with_capacity(chunks.len() + 1);
    for (s, e) in chunks {
        let mut c = s;
        while e - c > max_len + frame {
            out.push((c, c + max_len));
            c += max_len;
        }
        if e > c {
            out.push((c, e));
        }
    }
    out
}

/// Word error rate in percent: 100 * edit_distance(ref, hyp) /
/// |ref|. Words are lowercased alphanumeric (+ apostrophe). `None`
/// when the reference holds no words.
pub fn wer_percent(reference: &str, hypothesis: &str) -> Option<f32> {
    fn norm(s: &str) -> Vec<String> {
        s.split_whitespace()
            .map(|w| {
                w.to_lowercase()
                    .chars()
                    .filter(|c| c.is_alphanumeric() || *c == '\'')
                    .collect::<String>()
            })
            .filter(|w: &String| !w.is_empty())
            .collect()
    }
    let r = norm(reference);
    let h = norm(hypothesis);
    if r.is_empty() {
        return None;
    }
    let (n, m) = (r.len(), h.len());
    let mut prev: Vec<usize> = (0..=m).collect();
    let mut cur = vec![0usize; m + 1];
    for i in 1..=n {
        cur[0] = i;
        for j in 1..=m {
            cur[j] = (prev[j] + 1)
                .min(cur[j - 1] + 1)
                .min(prev[j - 1] + usize::from(r[i - 1] != h[j - 1]));
        }
        std::mem::swap(&mut prev, &mut cur);
    }
    Some(prev[m] as f32 / n as f32 * 100.0)
}

/// True when a measured WER meets the ship gate.
pub fn wer_passes(wer_pct: f32) -> bool {
    wer_pct <= WER_PCT_GATE
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(secs: f32, amp: i16) -> Vec<i16> {
        // 440 Hz sine at 16 kHz: RMS well above the VAD threshold.
        let n = (secs * 16000.0) as usize;
        (0..n)
            .map(|i| {
                let t = i as f32 / 16000.0;
                (amp as f32 * (2.0 * std::f32::consts::PI * 440.0 * t).sin()) as i16
            })
            .collect()
    }

    #[test]
    fn sha256_known_vector() {
        assert_eq!(
            format!("{:x}", Sha256::digest(b"abc")),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn missing_pack_fails_closed() {
        let dir = std::env::temp_dir().join(format!("mw-local-missing-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let err = verify_pack(&dir).unwrap_err();
        assert!(err.contains("missing"), "{err}");
        assert!(try_transcribe(&dir, &[0i16; 160]).is_err());
    }

    #[test]
    fn pinned_pack_rejects_wrong_content() {
        let dir = std::env::temp_dir().join(format!("mw-local-present-{}", std::process::id()));
        let pack = dir.join(PACK_REL_DIR);
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&pack).unwrap();
        for n in PACK_FILES {
            std::fs::write(pack.join(n), b"fake-bytes").unwrap();
        }
        // Pins are live: fake content fails closed on sha mismatch.
        let err = verify_pack(&dir).unwrap_err();
        assert!(err.contains("sha mismatch"), "{err}");
        assert!(try_transcribe(&dir, &[0i16; 160]).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn short_audio_is_one_chunk() {
        let pcm = tone(3.0, 10000);
        let chunks = chunk_pcm16(&pcm, 16000);
        assert_eq!(chunks.len(), 1, "{chunks:?}");
        assert_eq!(chunks[0], (0, pcm.len()));
    }

    #[test]
    fn silence_gap_splits_chunks() {
        let mut pcm = tone(3.0, 10000);
        pcm.extend(std::iter::repeat_n(0, 16000)); // 1s silence
        pcm.extend(tone(3.0, 10000));
        let chunks = chunk_pcm16(&pcm, 16000);
        assert_eq!(chunks.len(), 2, "{chunks:?}");
        for (s, e) in &chunks {
            assert!(*e - *s >= 16000, "chunk avoids slivers: {chunks:?}");
        }
    }

    #[test]
    fn long_audio_capped_under_bug_limit() {
        let pcm = tone(20.0, 10000);
        let chunks = chunk_pcm16(&pcm, 16000);
        assert!(chunks.len() >= 3, "{chunks:?}");
        let cap = (MAX_CHUNK_SECS * 16000.0) as usize + VAD_FRAME_SAMPLES;
        for (s, e) in &chunks {
            assert!(e - s <= cap, "chunk {s}..{e} exceeds cap");
        }
        // Full coverage, no gaps/overlaps.
        assert_eq!(chunks[0].0, 0);
        assert_eq!(chunks.last().unwrap().1, pcm.len());
        for w in chunks.windows(2) {
            assert_eq!(w[0].1, w[1].0, "{chunks:?}");
        }
    }

    #[test]
    fn silence_only_and_empty_yield_no_chunks() {
        assert!(chunk_pcm16(&vec![0i16; 32000], 16000).is_empty());
        assert!(chunk_pcm16(&[], 16000).is_empty());
        assert!(chunk_pcm16(&tone(1.0, 10000), 0).is_empty());
    }

    #[test]
    fn wer_identical_is_zero() {
        assert_eq!(wer_percent("Hello, world!", "hello world"), Some(0.0));
    }

    #[test]
    fn wer_counts_edits_over_ref_words() {
        // 1 insertion / 2 ref words = 50%.
        assert_eq!(wer_percent("hello world", "hello there world"), Some(50.0));
        // 1 substitution / 3 ref words.
        let w = wer_percent("a b c", "a x c").unwrap();
        assert!((w - 100.0 / 3.0).abs() < 0.01, "{w}");
        assert!(wer_passes(0.0));
        assert!(!wer_passes(WER_PCT_GATE + 0.01));
    }

    #[test]
    fn wer_empty_reference_is_none() {
        assert_eq!(wer_percent("", "hello"), None);
        assert_eq!(wer_percent("  ", ""), None);
    }
}
