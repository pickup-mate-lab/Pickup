"""Small, local-only pickup coordination server for the prototype.

The server keeps sessions in memory and uses HTTP. It is deliberately not a
production location-sharing service. Run behind HTTPS before testing on a
physical phone or using real locations outside a trusted local environment.
"""

from __future__ import annotations

import argparse
import hmac
import json
import math
import re
import secrets
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit


SESSION_SECONDS = 2 * 60 * 60
MAX_BODY_BYTES = 8 * 1024
SESSION_PATH = re.compile(r"^/api/sessions/([A-Za-z0-9_-]{6,40})(?:/(pickup|confirm|location|location-consent|finish))?$")
PASSENGER_PATH = re.compile(r"^/p/([A-Za-z0-9_-]{6,40})/([A-Za-z0-9_-]{20,100})$")
STATIC_DIR = Path(__file__).with_name("static")


def timestamp(epoch: float) -> str:
    return datetime.fromtimestamp(epoch, timezone.utc).isoformat().replace("+00:00", "Z")


def valid_text(value: object, field: str, maximum: int, optional: bool = False) -> str | None:
    if optional and value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"{field} must be text.")
    value = value.strip()
    if not value and not optional:
        raise ValueError(f"{field} is required.")
    if len(value) > maximum:
        raise ValueError(f"{field} is too long.")
    return value


def valid_coordinate(value: object, field: str, minimum: float, maximum: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{field} must be a finite number.")
    value = float(value)
    if not minimum <= value <= maximum:
        raise ValueError(f"{field} is outside its valid range.")
    return value


class SessionStore:
    def __init__(self) -> None:
        self._sessions: dict[str, dict] = {}
        self._lock = threading.RLock()

    def _expire(self, session: dict) -> None:
        if session["status"] not in ("finished", "expired") and time.time() >= session["expires_epoch"]:
            session["status"] = "expired"
            session["driverLocation"] = None
            session["passengerLocation"] = None
            session["passengerSharing"] = False
            session["etaSeconds"] = None

    def create(self, payload: dict) -> dict:
        contact = valid_text(payload.get("contactName"), "contactName", 80)
        pickup_time = valid_text(payload.get("pickupTime"), "pickupTime", 80, optional=True)
        now = time.time()
        with self._lock:
            while True:
                session_id = secrets.token_urlsafe(7)
                if session_id not in self._sessions:
                    break
            session = {
                "id": session_id,
                "contactName": contact,
                "pickupTime": pickup_time,
                "driverToken": secrets.token_urlsafe(32),
                "passengerToken": secrets.token_urlsafe(32),
                "createdAt": timestamp(now),
                "expiresAt": timestamp(now + SESSION_SECONDS),
                "expires_epoch": now + SESSION_SECONDS,
                "status": "invited",
                "pickup": None,
                "revision": 0,
                "driverLocation": None,
                "passengerLocation": None,
                "passengerSharing": False,
                "etaSeconds": None,
            }
            self._sessions[session_id] = session
            return {
                "id": session_id,
                "driverToken": session["driverToken"],
                "passengerToken": session["passengerToken"],
                "expiresAt": session["expiresAt"],
            }

    def access(self, session_id: str, token: str) -> tuple[dict | None, str | None]:
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return None, None
            self._expire(session)
            if hmac.compare_digest(token, session["driverToken"]):
                return session, "driver"
            if hmac.compare_digest(token, session["passengerToken"]):
                return session, "passenger"
            return None, None

    def snapshot(self, session_id: str, token: str) -> dict | None:
        with self._lock:
            session, role = self.access(session_id, token)
            if session is None:
                return None
            visible = session["status"] in ("pickup_pending", "active")
            return {
                "id": session["id"],
                "contactName": session["contactName"],
                "pickupTime": session["pickupTime"],
                "status": session["status"],
                "pickup": dict(session["pickup"]) if session["pickup"] else None,
                "driverLocation": dict(session["driverLocation"]) if visible and session["driverLocation"] else None,
                "passengerLocation": dict(session["passengerLocation"]) if role == "driver" and visible and session["passengerLocation"] else None,
                "passengerLocationSharing": session["passengerSharing"] if visible else False,
                "etaSeconds": session["etaSeconds"] if visible else None,
                "expiresAt": session["expiresAt"],
            }

    def update(self, session_id: str, token: str, action: str, payload: dict) -> dict:
        with self._lock:
            session, role = self.access(session_id, token)
            if session is None:
                raise PermissionError("Session or token was not found.")
            if session["status"] in ("finished", "expired"):
                raise RuntimeError("This pickup session has ended.")

            if action == "pickup":
                if role != "passenger":
                    raise PermissionError("Only the passenger can set the pickup point.")
                lat = valid_coordinate(payload.get("lat"), "lat", -90, 90)
                lon = valid_coordinate(payload.get("lon"), "lon", -180, 180)
                label = valid_text(payload.get("label"), "label", 120)
                session["revision"] += 1
                session["pickup"] = {
                    "lat": lat,
                    "lon": lon,
                    "label": label,
                    "revision": session["revision"],
                    "confirmed": False,
                    "updatedAt": timestamp(time.time()),
                }
                session["status"] = "pickup_pending"
                session["etaSeconds"] = None

            elif action == "confirm":
                if role != "driver":
                    raise PermissionError("Only the driver can confirm the pickup point.")
                revision = payload.get("revision")
                if isinstance(revision, bool) or not isinstance(revision, int):
                    raise ValueError("revision must be an integer.")
                if not session["pickup"]:
                    raise RuntimeError("The passenger has not set a pickup point.")
                if revision != session["pickup"]["revision"]:
                    raise RuntimeError("The pickup point changed. Refresh and confirm the latest one.")
                session["pickup"]["confirmed"] = True
                session["status"] = "active"

            elif action == "location-consent":
                if role != "passenger":
                    raise PermissionError("Only the passenger can change their location sharing.")
                enabled = payload.get("enabled")
                if not isinstance(enabled, bool):
                    raise ValueError("enabled must be true or false.")
                if enabled and not session["pickup"]:
                    raise RuntimeError("Choose a pickup point before sharing your location.")
                session["passengerSharing"] = enabled
                if not enabled:
                    session["passengerLocation"] = None

            elif action == "location":
                if role == "passenger" and session["status"] == "invited":
                    raise RuntimeError("Accept the invitation and choose a pickup point first.")
                if role == "passenger" and not session["passengerSharing"]:
                    raise RuntimeError("Passenger location sharing is off.")
                lat = valid_coordinate(payload.get("lat"), "lat", -90, 90)
                lon = valid_coordinate(payload.get("lon"), "lon", -180, 180)
                eta_update = session["etaSeconds"]
                if role == "driver" and "etaSeconds" in payload:
                    eta = payload["etaSeconds"]
                    if eta is None:
                        eta_update = None
                    elif isinstance(eta, bool) or not isinstance(eta, int) or not 0 <= eta <= 86400:
                        raise ValueError("etaSeconds must be an integer between 0 and 86400.")
                    else:
                        eta_update = eta
                session[role + "Location"] = {"lat": lat, "lon": lon, "updatedAt": timestamp(time.time())}
                if role == "driver":
                    session["etaSeconds"] = eta_update

            elif action == "finish":
                if role != "driver":
                    raise PermissionError("Only the driver can finish the pickup.")
                session["status"] = "finished"
                session["driverLocation"] = None
                session["passengerLocation"] = None
                session["passengerSharing"] = False
                session["etaSeconds"] = None
            else:
                raise ValueError("Unknown action.")

            return self.snapshot(session_id, token)


def make_handler(store: SessionStore):
    class PickupHandler(BaseHTTPRequestHandler):
        server_version = "PickupMateDemo/0.1"

        def log_message(self, format: str, *args: object) -> None:
            # Invite URLs contain a token. Never write request paths to logs.
            pass

        def _headers(self, status: int, content_type: str, length: int) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(length))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' https://tile.openstreetmap.org data:; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'")
            self.end_headers()

        def _send(self, status: int, body: bytes, content_type: str) -> None:
            self._headers(status, content_type, len(body))
            self.wfile.write(body)

        def _json(self, status: int, value: dict) -> None:
            self._send(status, json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"), "application/json; charset=utf-8")

        def _error(self, status: int, message: str) -> None:
            self._json(status, {"error": message})

        def _token(self) -> str:
            value = self.headers.get("Authorization", "")
            return value[7:] if value.startswith("Bearer ") else ""

        def _body(self) -> dict:
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError as exc:
                raise ValueError("Invalid Content-Length.") from exc
            if not 0 < length <= MAX_BODY_BYTES:
                raise ValueError("Request body is missing or too large.")
            try:
                body = json.loads(self.rfile.read(length).decode("utf-8"))
            except (UnicodeError, json.JSONDecodeError) as exc:
                raise ValueError("Request body must be valid JSON.") from exc
            if not isinstance(body, dict):
                raise ValueError("Request body must be an object.")
            return body

        def do_GET(self) -> None:
            path = unquote(urlsplit(self.path).path)
            if path == "/health":
                self._json(200, {"status": "ok"})
                return
            if path in ("/static/passenger.js", "/static/passenger.css"):
                asset = STATIC_DIR / path.rsplit("/", 1)[1]
                content_type = "text/javascript; charset=utf-8" if path.endswith(".js") else "text/css; charset=utf-8"
                self._send(200, asset.read_bytes(), content_type)
                return
            passenger = PASSENGER_PATH.fullmatch(path)
            if passenger:
                session_id, token = passenger.groups()
                if store.snapshot(session_id, token) is None:
                    self._error(404, "Invitation was not found.")
                    return
                self._send(200, (STATIC_DIR / "passenger.html").read_bytes(), "text/html; charset=utf-8")
                return
            match = SESSION_PATH.fullmatch(path)
            if match and not match.group(2):
                snapshot = store.snapshot(match.group(1), self._token())
                if snapshot is None:
                    self._error(401, "Session or token was not found.")
                else:
                    self._json(200, snapshot)
                return
            self._error(404, "Not found.")

        def do_POST(self) -> None:
            path = unquote(urlsplit(self.path).path)
            try:
                payload = self._body()
                if path == "/api/sessions":
                    self._json(201, store.create(payload))
                    return
                match = SESSION_PATH.fullmatch(path)
                if not match or not match.group(2):
                    self._error(404, "Not found.")
                    return
                self._json(200, store.update(match.group(1), self._token(), match.group(2), payload))
            except ValueError as exc:
                self._error(400, str(exc))
            except PermissionError as exc:
                self._error(403, str(exc))
            except RuntimeError as exc:
                self._error(409, str(exc))

    return PickupHandler


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the local Pickup Mate prototype server.")
    parser.add_argument("--host", default="127.0.0.1", help="Bind address (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8787, help="Port (default: 8787)")
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), make_handler(SessionStore()))
    print(f"Pickup Mate demo server listening at http://{args.host}:{args.port}")
    print("Sessions are in memory and HTTP is unencrypted. Use test locations only.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
