#!/usr/bin/env python3
"""Loopback-only OTP delivery adapter for disposable QuantaEdge E2E runs.

This provider never sends SMS or calls an external service. It accepts the real API's
provider contract, keeps synthetic OTPs in process memory, and exposes the latest
code only to authenticated test-runner queries. Do not use outside docker-compose.e2e.yml.
"""
from __future__ import annotations

import hmac
import json
import os
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HOST = "0.0.0.0"
PORT = int(os.environ.get("OTP_MOCK_PORT", "8090"))
TOKEN = os.environ.get("OTP_MOCK_TOKEN", "")
MAX_BODY = 16 * 1024
ALLOWED_PURPOSES = {"LOGIN", "SIGNUP", "STAFF_LOGIN", "PASSWORD_RESET", "ACCOUNT_SIGNUP"}
_codes: dict[tuple[str, str], dict[str, str]] = {}
_lock = threading.Lock()


def write_json(handler: BaseHTTPRequestHandler, status: int, payload: dict[str, object]) -> None:
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Cache-Control", "no-store")
    handler.send_header("Content-Length", str(len(raw)))
    handler.end_headers()
    handler.wfile.write(raw)


def authorized(handler: BaseHTTPRequestHandler) -> bool:
    supplied = handler.headers.get("Authorization", "")
    expected = "Bearer " + TOKEN if TOKEN else ""
    return bool(expected) and hmac.compare_digest(supplied, expected)


class Handler(BaseHTTPRequestHandler):
    server_version = "QE-E2E-OTP-Mock/1.0"
    sys_version = ""

    def log_message(self, fmt: str, *args: object) -> None:
        # Intentionally suppress default URL/body-adjacent logs; OTPs and numbers
        # must never appear in runner logs or uploaded artifacts.
        return

    def do_GET(self) -> None:
        route = urlparse(self.path)
        if route.path == "/health":
            write_json(self, 200 if TOKEN else 503, {"ready": bool(TOKEN)})
            return
        if route.path != "/latest":
            write_json(self, 404, {"error": "not_found"})
            return
        if not authorized(self):
            write_json(self, 401, {"error": "unauthorized"})
            return
        query = parse_qs(route.query, keep_blank_values=True)
        recipient = query.get("to", [""])[0]
        purpose = query.get("purpose", [""])[0]
        if not recipient or purpose not in ALLOWED_PURPOSES:
            write_json(self, 400, {"error": "invalid_lookup"})
            return
        with _lock:
            record = _codes.get((recipient, purpose))
        if record is None:
            write_json(self, 404, {"error": "code_not_available"})
            return
        write_json(self, 200, {"otp": record["otp"], "purpose": purpose})

    def do_POST(self) -> None:
        if urlparse(self.path).path != "/send":
            write_json(self, 404, {"error": "not_found"})
            return
        if not authorized(self):
            write_json(self, 401, {"error": "unauthorized"})
            return
        raw_length = self.headers.get("Content-Length", "")
        try:
            length = int(raw_length)
        except ValueError:
            write_json(self, 400, {"error": "invalid_length"})
            return
        if length < 1 or length > MAX_BODY:
            write_json(self, 413, {"error": "payload_too_large"})
            return
        try:
            body = json.loads(self.rfile.read(length))
        except (json.JSONDecodeError, UnicodeDecodeError):
            write_json(self, 400, {"error": "invalid_json"})
            return
        recipient = str(body.get("to", "")).strip()
        otp = str(body.get("otp", "")).strip()
        purpose = str(body.get("purpose", "")).strip()
        if not re.fullmatch(r"\+91[0-9]{10}", recipient) or not re.fullmatch(r"[0-9]{6}", otp) or purpose not in ALLOWED_PURPOSES:
            write_json(self, 400, {"error": "invalid_otp_request"})
            return
        with _lock:
            _codes[(recipient, purpose)] = {"otp": otp}
        # Never echo the OTP or full recipient in this response.
        write_json(self, 202, {"accepted": True})


if __name__ == "__main__":
    if not TOKEN:
        raise SystemExit("OTP_MOCK_TOKEN must be configured; refusing unauthenticated mock delivery.")
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.serve_forever()
