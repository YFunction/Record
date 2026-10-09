import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import secrets
import sqlite3
import struct
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
sys.path.insert(0, str(ROOT / "tools"))
from server import MAX_CHUNK, DOCUMENT_NAME, Server, Store, StoreError
from recover import decrypt_chunk, download, recover
from text_recover import decrypt_document, recover_documents

SESSION = "10000000-0000-4000-8000-000000000001"


def envelope(key, index=0, audio=b"\xff\xf1audio", final=False):
    name = f"{SESSION}_{index:08d}.enc"
    meta = {"version": 1, "codec": "aac-adts", "session": SESSION, "index": index, "final": final}
    data = json.dumps(meta).encode()
    nonce = secrets.token_bytes(12)
    blob = b"ER01" + nonce + AESGCM(key).encrypt(nonce, b"EAA1" + struct.pack(">I", len(data)) + data + audio, name.encode())
    return name, blob


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        base = (ROOT / ".tools" / "test-tmp").resolve()
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base)
        self.root = Path(self.temp.name)
        self.key = secrets.token_bytes(32)
        self.store = Store(self.root / "cloud", 10 * 1024 * 1024)
        self.token = secrets.token_urlsafe(32)
        self.server = Server(("127.0.0.1", 0), self.store, self.token)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.store.close()
        if not self.root.resolve().is_relative_to((ROOT / ".tools" / "test-tmp").resolve()):
            raise RuntimeError("Refusing to clean a temporary directory outside the test workspace")
        self.temp.cleanup()

    def text_envelope(self):
        name = f"{SESSION}_20000000-0000-4000-8000-000000000001.enc"
        nonce = secrets.token_bytes(12)
        text = json.dumps({'version': 1, 'session': SESSION, 'updatedAt': 123, 'segments': [{'speaker': 0, 'text': 'synthetic transcript'}], 'summary': 'synthetic summary'}).encode()
        blob = b'ET01' + nonce + AESGCM(self.key).encrypt(nonce, text, ('text-v1:' + name).encode())
        return name, blob, text

    def test_encrypted_text_upload_retry_download_and_recovery(self):
        name, blob, text = self.text_envelope()
        code, _ = self.request('/v1/documents/' + name, blob); self.assertEqual(code, 201)
        code, _ = self.request('/v1/documents/' + name, blob); self.assertEqual(code, 200)
        code, listing = self.request('/v1/documents'); self.assertEqual(code, 200)
        self.assertEqual(json.loads(listing)['documents'][0]['name'], name)
        stored = self.server.documents.blobs.joinpath(name).read_bytes()
        self.assertNotIn(b'synthetic transcript', stored); self.assertNotIn(b'synthetic summary', stored)
        self.assertEqual(AESGCM(self.key).decrypt(stored[4:16], stored[16:], ('text-v1:' + name).encode()), text)
        code, downloaded = self.request('/v1/documents/' + name); self.assertEqual(code, 200); self.assertEqual(downloaded, blob)
        changed = bytearray(blob); changed[-1] ^= 1
        code, _ = self.request('/v1/documents/' + name, bytes(changed)); self.assertEqual(code, 409)

    def test_text_namespace_and_authentication_are_separate(self):
        name, blob, _ = self.text_envelope()
        code, _ = self.request('/v1/documents/' + name, blob, token='invalid'); self.assertEqual(code, 401)
        audio_name, audio = envelope(self.key)
        code, _ = self.request('/v1/documents/' + name, audio); self.assertEqual(code, 400)
        code, _ = self.request('/v1/chunks/' + audio_name, blob); self.assertEqual(code, 400)
        code, _ = self.request('/v1/documents/' + name, blob); self.assertEqual(code, 201)
        code, listing = self.request('/v1/chunks'); self.assertEqual(code, 200); self.assertEqual(json.loads(listing)['chunks'], [])
        code, _ = self.request('/v1/documents?after=../secret'); self.assertEqual(code, 400)

    def test_text_quota_does_not_change_audio_quota(self):
        self.server.documents.capacity = 40
        name, blob, _ = self.text_envelope()
        code, _ = self.request('/v1/documents/' + name, blob); self.assertEqual(code, 507)
        audio_name, audio = envelope(self.key)
        code, _ = self.request('/v1/chunks/' + audio_name, audio); self.assertEqual(code, 201)

    def test_text_index_persists_when_storage_reopens(self):
        name, blob, _ = self.text_envelope(); self.request('/v1/documents/' + name, blob)
        self.server.documents.close()
        self.server.documents = Store(self.store.root / 'texts', 256 * 1024**2, DOCUMENT_NAME, b'ET01')
        code, downloaded = self.request('/v1/documents/' + name); self.assertEqual(code, 200); self.assertEqual(downloaded, blob)

    def test_text_downloader_and_recovery_tool_round_trip(self):
        name, blob, text = self.text_envelope(); self.assertEqual(self.request('/v1/documents/' + name, blob)[0], 201)
        encrypted = self.root / 'text-download'; output = self.root / 'text-plain'
        download(self.url, encrypted, self.token, documents=True)
        download(self.url, encrypted, self.token, documents=True)
        self.assertEqual((encrypted / name).read_bytes(), blob)
        self.assertEqual(recover_documents(encrypted, output, self.key), 1)
        self.assertEqual(json.loads((output / (name[:-4] + '.json')).read_text()), json.loads(text))
        with self.assertRaisesRegex(ValueError, 'already exists'): recover_documents(encrypted, output, self.key)

    def test_text_tampering_wrong_key_and_rename_rejected_before_export(self):
        name, blob, _ = self.text_envelope()
        for key, filename, data in [(secrets.token_bytes(32), name, blob), (self.key, name.replace('20000000', '30000000'), blob), (self.key, name, blob[:-1] + bytes([blob[-1] ^ 1]))]:
            with self.assertRaises(InvalidTag): decrypt_document(key, filename, data)
        source = self.root / 'bad-text'; source.mkdir(); (source / name).write_bytes(blob[:-1] + bytes([blob[-1] ^ 1]))
        with self.assertRaises(InvalidTag): recover_documents(source, self.root / 'text-plain', self.key)
        self.assertFalse((self.root / 'text-plain').exists())

    def request(self, path, blob=None, token=None, digest=None):
        headers = {"Authorization": "Bearer " + (self.token if token is None else token)}
        if blob is not None:
            headers["X-Content-SHA256"] = digest or hashlib.sha256(blob).hexdigest()
        req = urllib.request.Request(self.url + path, data=blob, headers=headers, method="PUT" if blob is not None else "GET")
        try:
            with urllib.request.urlopen(req, timeout=5) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as response:
            return response.code, response.read()

    def test_end_to_end_retry_download_and_offline_recovery(self):
        # A 30-second slice at 64 kbps is about 240 KB. This tests transport and
        # encryption of that size; real AAC capture/playback still needs a phone.
        audio = b"\xff\xf1" + secrets.token_bytes(30 * 64000 // 8)
        name, blob = envelope(self.key, audio=audio)
        end, marker = envelope(self.key, index=1, audio=b"", final=True)
        code, receipt = self.request("/v1/chunks/" + name, blob)
        self.assertEqual(code, 201)
        self.assertEqual(json.loads(receipt)["sha256"], hashlib.sha256(blob).hexdigest())
        self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 200)
        self.assertEqual(self.request("/v1/chunks/" + end, marker)[0], 201)
        encrypted = self.root / "download"
        download(self.url, encrypted, self.token)
        download(self.url, encrypted, self.token)  # resumable, unchanged files retained
        self.assertEqual(recover(encrypted, self.root / "plain", self.key), 1)
        self.assertEqual((self.root / "plain" / (SESSION + ".aac")).read_bytes(), audio)
        cloud = b"".join(p.read_bytes() for p in self.store.blobs.glob("*.enc"))
        self.assertNotIn(audio, cloud)
        self.assertNotIn(self.key, cloud)

    def test_authentication_checksums_and_conflicts(self):
        name, blob = envelope(self.key)
        self.assertEqual(self.request("/v1/chunks/" + name, blob, token="wrong")[0], 401)
        self.assertEqual(self.request("/v1/chunks")[0], 200)
        self.assertEqual(self.request("/v1/chunks", token="wrong")[0], 401)
        self.assertEqual(self.request("/v1/chunks/" + name, blob, digest="0" * 64)[0], 422)
        self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 201)
        _, other = envelope(self.key)
        self.assertEqual(self.request("/v1/chunks/" + name, other)[0], 409)
        self.assertEqual(self.store.get(name)[0], blob)

    def test_tampering_wrong_key_and_renaming_fail_before_plaintext(self):
        name, blob = envelope(self.key)
        for key, filename, data in [(secrets.token_bytes(32), name, blob),
                                     (self.key, name.replace("00000000.enc", "00000001.enc"), blob),
                                     (self.key, name, blob[:-1] + bytes([blob[-1] ^ 1]))]:
            with self.assertRaises(InvalidTag):
                decrypt_chunk(key, filename, data)
        source = self.root / "encrypted"
        source.mkdir()
        (source / name).write_bytes(blob[:-1] + bytes([blob[-1] ^ 1]))
        with self.assertRaises(InvalidTag):
            recover(source, self.root / "plain", self.key)
        self.assertFalse((self.root / "plain").exists())

    def test_missing_chunk_and_unfinished_sessions_are_detected(self):
        source = self.root / "encrypted"
        source.mkdir()
        name, blob = envelope(self.key)
        (source / name).write_bytes(blob)
        with self.assertRaisesRegex(ValueError, "no end marker"):
            recover(source, self.root / "plain", self.key)
        self.assertEqual(recover(source, self.root / "partial", self.key, True), 1)
        name, blob = envelope(self.key, index=2, audio=b"", final=True)
        (source / name).write_bytes(blob)
        with self.assertRaisesRegex(ValueError, "Missing chunks"):
            recover(source, self.root / "plain", self.key)

    def test_concurrent_duplicate_uploads_never_overwrite(self):
        name, blob = envelope(self.key)
        with ThreadPoolExecutor(max_workers=8) as pool:
            codes = list(pool.map(lambda _: self.request("/v1/chunks/" + name, blob)[0], range(8)))
        self.assertEqual(codes.count(201), 1)
        self.assertEqual(codes.count(200), 7)
        self.assertEqual(len(self.store.list("")["chunks"]), 1)

    def test_path_validation_limits_and_quota(self):
        name, blob = envelope(self.key)
        self.assertEqual(self.request("/v1/chunks/../../outside", blob)[0], 400)
        # Send an oversized declared body without transmitting it; rejection is immediate.
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            connection.putrequest("PUT", "/v1/chunks/" + name)
            connection.putheader("Authorization", "Bearer " + self.token)
            connection.putheader("Content-Length", str(MAX_CHUNK + 1))
            connection.endheaders()
            self.assertEqual(connection.getresponse().status, 413)
        finally:
            connection.close()
        self.store.capacity = len(blob) - 1
        self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 507)
        self.assertFalse((self.store.blobs / name).exists())

    def test_disk_failure_does_not_acknowledge_and_retry_recovers(self):
        name, blob = envelope(self.key)
        with patch("server.os.replace", side_effect=OSError("disk failed")):
            self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 503)
        self.assertEqual(self.store.list("")["chunks"], [])
        self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 201)
        (self.store.blobs / name).write_bytes(b"corrupted")
        self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 503)
        self.assertEqual(self.request("/v1/chunks/" + name)[0], 503)

    def test_orphan_file_recovery_and_index_survives_restart(self):
        name, blob = envelope(self.key)
        (self.store.blobs / name).write_bytes(blob)  # crash after rename before index commit
        self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 201)
        other = Store(self.store.root, self.store.capacity)
        try:
            self.assertEqual(other.get(name)[0], blob)
        finally:
            other.close()

    def test_index_failure_never_returns_success_and_retry_reindexes(self):
        name, blob = envelope(self.key)
        with self.store.lock, self.store.db:
            self.store.db.execute("CREATE TRIGGER fail_index BEFORE INSERT ON chunks BEGIN SELECT RAISE(ABORT, 'simulated failure'); END")
        self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 503)
        self.assertEqual(self.store.list("")["chunks"], [])
        with self.store.lock, self.store.db:
            self.store.db.execute("DROP TRIGGER fail_index")
        self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 201)
        self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 200)

    def test_directory_sync_failure_is_not_acknowledged(self):
        name, blob = envelope(self.key)
        with patch("server.sync_directory", side_effect=OSError("fsync failed")):
            self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 503)
        self.assertEqual(self.store.list("")["chunks"], [])
        self.assertEqual(self.request("/v1/chunks/" + name, blob)[0], 201)

    def test_listing_pagination(self):
        with self.store.lock, self.store.db:
            self.store.db.executemany("INSERT INTO chunks VALUES (?, ?, ?, ?)",
                [(f"{SESSION}_{i:08d}.enc", "a" * 64, 32, 0) for i in range(501)])
        first = self.store.list("")
        self.assertEqual(len(first["chunks"]), 500)
        self.assertIsNotNone(first["next"])
        second = self.store.list(first["next"])
        self.assertEqual(len(second["chunks"]), 1)
        self.assertIsNone(second["next"])


if __name__ == "__main__":
    unittest.main()
