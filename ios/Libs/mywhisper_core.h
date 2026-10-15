// mywhisper_core.h — C-ABI surface of `mywhisper-core` for Swift.
//
// Authoritative header for `core/src/capple.rs`. Committed under
// `ios/Libs/` so `scripts/build-core-ios.sh` can feed it straight to
// `xcodebuild -create-xcframework -headers`, and Xcode links the
// resulting `ios/Libs/libmywhisper_core.xcframework`.
//
// Envelope semantics (same as JNI `lib.rs`): functions returning the
// envelope produce `{"ok":true,"data":...}` or `{"ok":false,"error":...}`.
// `mywhisper_get_settings`, `mywhisper_get_history`, `mywhisper_get_words`
// and `mywhisper_get_snippets` return the raw payload (no envelope),
// exactly like their `native*` JNI twins.
//
// Ownership: every returned `char *` is heap-allocated — release it with
// `mywhisper_free_string`. Never free NULL, never use after free.

#ifndef MYWHISPER_CORE_H
#define MYWHISPER_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void mywhisper_free_string(char *s);

// Plain version string (CARGO_PKG_VERSION), not enveloped.
char *mywhisper_core_version(void);

// {"ok","data":{"version"}}. Must be called before any other method.
char *mywhisper_engine_open(const char *files_dir, const char *db_key_hex);

// Raw settings JSON (no envelope).
char *mywhisper_get_settings(void);

// Envelope (data: null on success).
char *mywhisper_put_settings(const char *json);

// Envelope (data: null). sample_rate in Hz (16000 typical).
char *mywhisper_start_utterance(uint32_t sample_rate);

// Envelope (data: null). Copies `len` int16 samples.
char *mywhisper_push_pcm(const int16_t *data, size_t len);

// Block while the server chain transcribes (call off the main thread).
// {"ok","data":{"text","raw","engine","durationSecs","words"}}.
char *mywhisper_finish_utterance(void);

// Envelope (data: {"durationSecs"}).
char *mywhisper_cancel_utterance(void);

// Raw history JSON array (no envelope), limit clamped to >= 1.
char *mywhisper_get_history(int limit);

// Envelope (data: {"removed"}).
char *mywhisper_clear_history(void);

// Envelope (data: null).
char *mywhisper_delete_history(const char *uuid);

// Raw words JSON array (no envelope).
char *mywhisper_get_words(void);

// Envelope (data: null).
char *mywhisper_add_word(const char *word);

// Envelope (data: null).
char *mywhisper_delete_word(const char *word);

// Raw snippets JSON array (no envelope).
char *mywhisper_get_snippets(void);

// Envelope (data: null).
char *mywhisper_put_snippet(const char *trigger, const char *replacement);

// Envelope (data: null).
char *mywhisper_delete_snippet(const char *trigger);

#ifdef __cplusplus
}
#endif

#endif // MYWHISPER_CORE_H
