#!/usr/bin/env python3
# Stub OpenAI-compatible STT server for emulator end-to-end tests.
# POST /v1/audio/transcriptions -> {"text": "..."} regardless of audio.
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

TEXT = "hello world test"


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        body = json.dumps({"text": TEXT}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


HTTPServer(("127.0.0.1", 8765), H).serve_forever()
