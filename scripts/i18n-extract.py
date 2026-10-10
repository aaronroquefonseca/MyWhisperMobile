#!/usr/bin/env python3
# One-shot i18n extraction: replace hardcoded UI literals with
# string resources. Exact-literal matches only; templated strings
# are handled manually afterwards. Run from repo root if ever needed
# again (idempotent-ish: skips lines already using R.string).
import re
import sys

M = {
    # actions
    "Add": "action_add",
    "Delete": "action_delete",
    "Edit": "action_edit",
    "Save": "action_save",
    "Cancel": "action_cancel",
    "Close": "action_close",
    "Remove": "action_remove",
    "Reset": "action_reset",
    "Accept": "action_accept",
    "Start": "action_start",
    "Stop": "action_stop",
    "Refresh": "action_refresh",
    "Clear": "action_clear",
    "Change": "action_change",
    "Show": "action_show",
    "Hide": "action_hide",
    "Got it": "action_gotit",
    "Sync": "action_sync",
    "Sync now": "action_sync_now",
    "Show less": "action_show_less",
    "Save prompt": "action_save_prompt",
    "Customize": "cleanup_customize",
    "Edit a copy of the default": "cleanup_customize",  # replaced by redesign; harmless
    "Reset to default": "cleanup_reset",
    "View default": "cleanup_customize",  # replaced by redesign; harmless
    "Save tap timing": "setup_tap_save",
    "Save capture": "stt_save_capture",
    "Delete older now": "hist_delete_older",
    "Send pairing request": "sync_add_send",
    "Add by ID": "sync_add_button",
    "Add snippet": "dict_add_snippet",
    "Add model": "cleanup_add",
    "Self-hosted": "stt_selfhosted",
    "Cloud": "stt_cloud",
    "Why?": "stt_local_why",
    # setup
    "Status": "setup_status",
    "Server-only build: configure a self-hosted or cloud speech service under Speech. On-device models come later.": "setup_server_only",
    "1 · Enable the input method": "setup_step1",
    "MyWhisper is enabled as an input method.": "setup_enabled_yes",
    "MyWhisper is not enabled yet.": "setup_enabled_no",
    "Open input-method settings": "setup_open_ime",
    "2 · Switch to it": "setup_step2",
    "Choose input method": "setup_pick",
    "3 · Microphone": "setup_step3",
    "Microphone granted.": "setup_mic_yes",
    "Microphone is required for dictation.": "setup_mic_no",
    "Grant microphone": "setup_mic_grant",
    "Mic permission is required for dictation": "setup_mic_denied",
    "4 · Notifications": "setup_step4",
    "Shows the sync status while sync runs.": "setup_notif_body",
    "Allow notifications": "setup_notif_allow",
    "Without notifications the sync status icon stays hidden": "setup_notif_denied",
    "Notifications are always on below Android 13": "setup_notif_old",
    "Mic button": "setup_mic_button",
    "Smart tap ceiling (ms)": "setup_tap_ceiling",
    "Preferences": "setup_prefs",
    # stt
    "On-device speech": "stt_local_title",
    "Capture": "stt_capture",
    "Language": "stt_language",
    "Voice activity trim": "stt_vad",
    "Name": "stt_name",
    "Model": "stt_model",
    "API key": "stt_apikey",
    "Provider": "stt_provider",
    "show": "stt_apikey_show",
    "hide": "stt_apikey_hide",
    "Enabled": "stt_enabled",
    "Server URL (http(s) or ws(s))": "stt_url_server",
    "Base URL (blank = vendor default)": "stt_url_cloud",
    "Streaming (ws servers)": "stt_streaming",
    "URL": "llm_url",
    "URL (blank = vendor default)": "llm_url_cloud",
    "Keep-alive (blank, -1, 0, 5m…)": "llm_keepalive",
    # cleanup / llm
    "Cleanup prompt": "cleanup_dialog_title",
    "Language model": "llm_kind",
    "Kind": "llm_kind",
    "Thinking": "llm_thinking",
    "Default": "llm_thinking_default",
    "On": "llm_thinking_on",
    "Off": "llm_thinking_off",
    # dict
    "New word": "dict_new_word",
    "That word is already listed.": "dict_dup",
    "Search words": "dict_search_words",
    "Search snippets": "dict_search_snippets",
    "No matches.": "dict_no_match",
    "Trigger (comma-separated allowed)": "dict_trigger",
    "Replacement": "dict_replacement",
    # activity
    "Less": "act_less",
    "More": "act_more",
    # history
    "Privacy & retention": "hist_privacy",
    "Keep history": "hist_keep",
    "Keep cancelled dictations": "hist_cancelled",
    "Search transcripts": "hist_search",
    # sync
    "This device": "sync_this",
    "Sync stopped.": "sync_stopped",
    "Advertise on LAN (mDNS)": "sync_advertise",
    "Sync enabled": "sync_enabled",
    "Start on boot": "sync_boot",
    "Trusted (": "sync_trusted",  # handled manually (count)
    "None yet.": "sync_none",
    "no address yet": "sync_noaddr",
    "None.": "sync_requests_none",
    "Nobody around.": "sync_nobody",
    "Refresh": "action_refresh",
}

TEXT_RE = re.compile(r'Text\("((?:[^"\\]|\\.)*)"\)')
CD_RE = re.compile(r'contentDescription = "((?:[^"\\]|\\.)*)"')


def sub_file(path, is_composable_file=True):
    src = open(path).read()
    out = []
    changed = 0
    for line in src.split("\n"):
        if "R.string." in line:
            out.append(line)
            continue
        m = TEXT_RE.search(line)
        if m and m.group(1) in M:
            key = M[m.group(1)]
            line = TEXT_RE.sub(f"Text(stringResource(R.string.{key}))", line, count=1)
            changed += 1
        else:
            m2 = CD_RE.search(line)
            if m2 and m2.group(1) in M:
                key = M[m2.group(1)]
                line = CD_RE.sub(
                    f"contentDescription = stringResource(R.string.{key})",
                    line,
                    count=1,
                )
                changed += 1
        out.append(line)
    open(path, "w").write("\n".join(out))
    print(f"{path}: {changed} replacements")


if __name__ == "__main__":
    base = "/home/aaronroque/Projects/MyWhisperMobile/android/app/src/main/kotlin/com/mywhisper/mobile"
    sub_file(f"{base}/MainActivity.kt")
    sub_file(f"{base}/MyWhisperIME.kt")
