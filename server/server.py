"""Single-user ciphertext store. Bind to loopback behind an HTTPS reverse proxy."""
from __future__ import annotations

import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

NAME = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_[0-9]{8}\.enc\Z")
DOCUMENT_NAME = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.enc\Z")
MAX_CHUNK = 2 * 1024 * 1024
MAX_CATALOG = 1024 * 1024


class StoreError(Exception):
    def __init__(self, status: int, message: str):
        self.status, self.message = status, message


def sync_directory(path: Path) -> None:
    if os.name == "posix":
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


class Store:
    def __init__(self, root: Path, capacity: int, name_pattern=NAME, magic=b"ER01"):
        self.name_pattern, self.magic = name_pattern, magic
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.blobs = self.root / "chunks"
        self.blobs.mkdir(mode=0o700, exist_ok=True)
        self.used = sum(p.stat().st_size for p in self.blobs.glob("*.enc"))
        self.capacity = capacity
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.root / "index.sqlite3", check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("CREATE TABLE IF NOT EXISTS chunks (name TEXT PRIMARY KEY, sha256 TEXT NOT NULL, size INTEGER NOT NULL, stored_at INTEGER NOT NULL)")
        self.db.commit()

    def put(self, name: str, payload: bytes, claimed: str) -> tuple[int, dict]:
        if not self.name_pattern.fullmatch(name):
            raise StoreError(400, "invalid chunk name")
        if not 32 <= len(payload) <= MAX_CHUNK or payload[:4] != self.magic:
            raise StoreError(400, "invalid envelope")
        digest = hashlib.sha256(payload).hexdigest()
        if not hmac.compare_digest(digest, claimed):
            raise StoreError(422, "checksum mismatch")
        with self.lock:
            existing = self.db.execute("SELECT sha256, size FROM chunks WHERE name=?", (name,)).fetchone()
            destination = self.blobs / name
            if existing is not None:
                if existing != (digest, len(payload)):
                    raise StoreError(409, "name already has different content")
                if not destination.is_file() or hashlib.sha256(destination.read_bytes()).hexdigest() != digest:
                    raise StoreError(503, "stored file needs administrator repair")
                return 200, self.receipt(name, digest, len(payload))
            # Include crash-orphaned blobs in quota; never silently overwrite different ciphertext.
            if destination.exists():
                if hashlib.sha256(destination.read_bytes()).hexdigest() != digest:
                    raise StoreError(409, "unindexed file has different content")
                with destination.open("r+b") as recovered:
                    os.fsync(recovered.fileno())
                sync_directory(self.blobs)
            else:
                if self.used + len(payload) > self.capacity:
                    raise StoreError(507, "storage quota exceeded")
                if shutil.disk_usage(self.root).free < len(payload) + 16 * 1024 * 1024:
                    raise StoreError(507, "disk space low")
                fd, temp = tempfile.mkstemp(prefix=".incoming-", dir=self.blobs)
                try:
                    with os.fdopen(fd, "wb") as output:
                        output.write(payload)
                        output.flush()
                        os.fsync(output.fileno())
                    os.replace(temp, destination)
                    self.used += len(payload)
                    sync_directory(self.blobs)
                finally:
                    if os.path.exists(temp):
                        os.unlink(temp)
            with self.db:
                self.db.execute("INSERT INTO chunks VALUES (?, ?, ?, ?)", (name, digest, len(payload), int(time.time())))
            return 201, self.receipt(name, digest, len(payload))

    @staticmethod
    def receipt(name: str, digest: str, size: int) -> dict:
        return {"stored": True, "name": name, "sha256": digest, "size": size}

    def list(self, after: str) -> dict:
        if after and not self.name_pattern.fullmatch(after):
            raise StoreError(400, "invalid cursor")
        with self.lock:
            rows = self.db.execute("SELECT name, sha256, size FROM chunks WHERE name > ? ORDER BY name LIMIT 501", (after,)).fetchall()
        return {"chunks": [{"name": r[0], "sha256": r[1], "size": r[2]} for r in rows[:500]],
                "next": rows[499][0] if len(rows) > 500 else None}

    def get(self, name: str) -> tuple[bytes, str]:
        if not self.name_pattern.fullmatch(name):
            raise StoreError(400, "invalid chunk name")
        with self.lock:
            row = self.db.execute("SELECT sha256 FROM chunks WHERE name=?", (name,)).fetchone()
            if row is None:
                raise StoreError(404, "not found")
            path = self.blobs / name
            if not path.is_file():
                raise StoreError(503, "stored file missing")
            data = path.read_bytes()
            if hashlib.sha256(data).hexdigest() != row[0]:
                raise StoreError(503, "stored file corrupted")
            return data, row[0]

    def close(self) -> None:
        self.db.close()


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 32

    def __init__(self, address, store: Store, token: str):
        self.store, self.token = store, token
        self.documents = Store(store.root / "texts", int(os.environ.get("RECORDER_TEXT_QUOTA_BYTES", str(256 * 1024**2))), DOCUMENT_NAME, b"ET01")
        self.catalog_path = store.root / "category-catalog.enc"
        self.catalog_lock = threading.RLock()
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(address, Handler)

    def put_catalog(self, payload: bytes, claimed: str) -> tuple[int, dict]:
        if not 32 <= len(payload) <= MAX_CATALOG or payload[:4] != b"CC01":
            raise StoreError(400, "invalid category catalog envelope")
        digest = hashlib.sha256(payload).hexdigest()
        if not hmac.compare_digest(digest, claimed):
            raise StoreError(422, "checksum mismatch")
        with self.catalog_lock:
            if self.catalog_path.is_file():
                existing = self.catalog_path.read_bytes()
                if existing == payload:
                    return 200, Store.receipt("category-catalog", digest, len(payload))
            if shutil.disk_usage(self.store.root).free < len(payload) + 16 * 1024 * 1024:
                raise StoreError(507, "disk space low")
            fd, temp = tempfile.mkstemp(prefix=".catalog-", dir=self.store.root)
            try:
                with os.fdopen(fd, "wb") as output:
                    output.write(payload)
                    output.flush()
                    os.fsync(output.fileno())
                os.replace(temp, self.catalog_path)
                sync_directory(self.store.root)
            finally:
                if os.path.exists(temp):
                    os.unlink(temp)
        return 201, Store.receipt("category-catalog", digest, len(payload))

    def get_catalog(self) -> tuple[bytes, str]:
        with self.catalog_lock:
            if not self.catalog_path.is_file():
                raise StoreError(404, "not found")
            payload = self.catalog_path.read_bytes()
            if not 32 <= len(payload) <= MAX_CATALOG or payload[:4] != b"CC01":
                raise StoreError(503, "stored catalog needs administrator repair")
            return payload, hashlib.sha256(payload).hexdigest()

    def server_close(self):
        super().server_close()
        self.documents.close()

    def get_request(self):
        sock, address = super().get_request()
        sock.settimeout(30)
        return sock, address

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    server_version = "CipherStore/1"

    def log_message(self, format, *args):
        # Avoid logging authorization headers, filenames, or audio metadata.
        pass

    def authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        return hmac.compare_digest(header.encode("utf-8"), ("Bearer " + self.server.token).encode("utf-8"))

    def respond(self, status: int, body: dict):
        data = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def do_PUT(self):
        try:
            if not self.authorized():
                raise StoreError(401, "unauthorized")
            path = urlsplit(self.path)
            if path.path == "/v1/category-catalog":
                if path.query or self.headers.get("Transfer-Encoding") is not None:
                    raise StoreError(400, "content length required")
                lengths = self.headers.get_all("Content-Length", [])
                if len(lengths) != 1 or not lengths[0].isdigit():
                    raise StoreError(411, "content length required")
                size = int(lengths[0])
                if not 32 <= size <= MAX_CATALOG:
                    raise StoreError(413, "invalid category catalog size")
                digest = self.headers.get("X-Content-SHA256", "")
                if not re.fullmatch(r"[0-9a-f]{64}", digest):
                    raise StoreError(400, "checksum required")
                payload = self.rfile.read(size)
                if len(payload) != size:
                    raise StoreError(400, "incomplete body")
                code, body = self.server.put_catalog(payload, digest)
                self.respond(code, body)
                return
            documents = path.path.startswith("/v1/documents/")
            prefix = "/v1/documents/" if documents else "/v1/chunks/"
            store = self.server.documents if documents else self.server.store
            if not path.path.startswith(prefix) or path.query:
                raise StoreError(404, "not found")
            name = path.path[len(prefix):]
            if not store.name_pattern.fullmatch(name):
                raise StoreError(400, "invalid chunk name")
            if self.headers.get("Transfer-Encoding") is not None:
                raise StoreError(400, "content length required")
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1 or not lengths[0].isdigit():
                raise StoreError(411, "content length required")
            size = int(lengths[0])
            if not 32 <= size <= MAX_CHUNK:
                raise StoreError(413, "invalid chunk size")
            digest = self.headers.get("X-Content-SHA256", "")
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise StoreError(400, "checksum required")
            payload = self.rfile.read(size)
            if len(payload) != size:
                raise StoreError(400, "incomplete body")
            code, body = store.put(name, payload, digest)
            self.respond(code, body)
        except StoreError as exc:
            self.respond(exc.status, {"error": exc.message})
        except (OSError, sqlite3.Error):
            self.respond(503, {"error": "storage unavailable"})

    def do_GET(self):
        try:
            if not self.authorized():
                raise StoreError(401, "unauthorized")
            path = urlsplit(self.path)
            if path.path == "/v1/category-catalog":
                if path.query:
                    raise StoreError(400, "invalid query")
                payload, digest = self.server.get_catalog()
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("X-Content-SHA256", digest)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(payload)
                return
            documents = path.path == "/v1/documents" or path.path.startswith("/v1/documents/")
            prefix = "/v1/documents" if documents else "/v1/chunks"
            store = self.server.documents if documents else self.server.store
            if path.path == prefix:
                query = parse_qs(path.query)
                if set(query) - {"after"}:
                    raise StoreError(400, "invalid query")
                body = store.list(query.get("after", [""])[0])
                if documents:
                    body["documents"] = body.pop("chunks")
                self.respond(200, body)
                return
            if not path.path.startswith(prefix + "/") or path.query:
                raise StoreError(404, "not found")
            payload, digest = store.get(path.path[len(prefix + "/"):])
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("X-Content-SHA256", digest)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(payload)
        except StoreError as exc:
            self.respond(exc.status, {"error": exc.message})
        except (OSError, sqlite3.Error):
            self.respond(503, {"error": "storage unavailable"})


def main():
    token = os.environ.get("RECORDER_TOKEN", "")
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", token):
        raise SystemExit("Set RECORDER_TOKEN to a random token (32-256 URL-safe characters).")
    store = Store(Path(os.environ.get("RECORDER_DATA", "./data")), int(os.environ.get("RECORDER_QUOTA_BYTES", str(10 * 1024**3))))
    server = Server((os.environ.get("RECORDER_BIND", "127.0.0.1"), int(os.environ.get("RECORDER_PORT", "8080"))), store, token)
    print(f"Ciphertext store listening on {server.server_address[0]}:{server.server_address[1]}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        store.close()


if __name__ == "__main__":
    main()
