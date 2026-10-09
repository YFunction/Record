"""Download authenticated ciphertext and decrypt offline using a separately backed-up key."""
from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import urllib.request
from urllib.parse import urlsplit

NAME = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_[0-9]{8}\.enc\Z")
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
DOCUMENT_NAME = re.compile(UUID + "_" + UUID + r"\.enc\Z")
MAX_CHUNK = 2 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def download(server: str, output: Path, token: str, documents: bool = False):
    pattern = DOCUMENT_NAME if documents else NAME
    collection = "documents" if documents else "chunks"
    address = urlsplit(server)
    if address.scheme != "https" and not (address.scheme == "http" and address.hostname in {"127.0.0.1", "localhost"}):
        raise ValueError("Use HTTPS; HTTP is allowed only on loopback for local tests")
    if address.username or address.password or address.query or address.fragment or address.path not in {"", "/"}:
        raise ValueError("Use the server root URL")
    base = server.rstrip("/")
    opener = urllib.request.build_opener(NoRedirect)
    output.mkdir(parents=True, exist_ok=True)

    def get(path, limit):
        request = urllib.request.Request(base + path, headers={"Authorization": "Bearer " + token})
        with opener.open(request, timeout=30) as response:
            data = response.read(limit + 1)
        if len(data) > limit:
            raise ValueError("Response too large")
        return data

    after = ""
    while True:
        page = json.loads(get("/v1/" + collection + ("?after=" + after if after else ""), 256 * 1024))
        for row in page[collection]:
            name, digest = row["name"], row["sha256"]
            if not pattern.fullmatch(name) or not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError("Invalid server index")
            target = output / name
            if target.exists():
                if hashlib.sha256(target.read_bytes()).hexdigest() == digest:
                    continue
                raise ValueError(f"Existing file differs: {name}")
            data = get("/v1/" + collection + "/" + name, MAX_CHUNK)
            if len(data) != row["size"] or hashlib.sha256(data).hexdigest() != digest:
                raise ValueError("Download checksum mismatch")
            with target.open("xb") as file:
                file.write(data)
        cursor = page.get("next")
        if cursor is None:
            return
        if not pattern.fullmatch(cursor) or cursor <= after:
            raise ValueError("Invalid pagination cursor")
        after = cursor


def decrypt_chunk(key: bytes, name: str, envelope: bytes):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    if not NAME.fullmatch(name) or not 32 <= len(envelope) <= MAX_CHUNK or envelope[:4] != b"ER01":
        raise ValueError("Invalid encrypted envelope")
    plaintext = AESGCM(key).decrypt(envelope[4:16], envelope[16:], name.encode())
    if plaintext[:4] != b"EAA1" or len(plaintext) < 8:
        raise ValueError("Invalid recording payload")
    length = struct.unpack(">I", plaintext[4:8])[0]
    if length > 4096 or length + 8 > len(plaintext):
        raise ValueError("Invalid metadata size")
    metadata = json.loads(plaintext[8:8 + length])
    session, index = name[:-4].rsplit("_", 1)
    if metadata.get("version") != 1 or metadata.get("codec") != "aac-adts" or metadata.get("session") != session or metadata.get("index") != int(index):
        raise ValueError("Metadata doesn't match filename")
    audio = plaintext[8 + length:]
    if metadata.get("final") not in {True, False} or (metadata["final"] and audio) or (not metadata["final"] and not audio):
        raise ValueError("Invalid session end marker")
    return metadata, audio


def recover(source: Path, output: Path, key: bytes, allow_incomplete: bool = False):
    if len(key) != 32:
        raise ValueError("Recovery key must contain 32 bytes")
    groups = {}
    # Authenticate the whole set before writing any plaintext to disk.
    for path in sorted(source.glob("*.enc")):
        meta, _ = decrypt_chunk(key, path.name, path.read_bytes())
        groups.setdefault(meta["session"], []).append((meta, path))
    if not groups:
        raise ValueError("No encrypted chunks found")
    for session, chunks in groups.items():
        indices = [m["index"] for m, _ in chunks]
        if indices != list(range(len(chunks))):
            raise ValueError(f"Missing chunks for {session}: indices={indices}")
        if any(meta["final"] for meta, _ in chunks[:-1]):
            raise ValueError(f"Unexpected session end marker in {session}")
        if not chunks[-1][0]["final"] and not allow_incomplete:
            raise ValueError(f"Session {session} has no end marker. Wait for upload completion or use --allow-incomplete to recover a partial recording")
        if (output / (session + ".aac")).exists() or (output / (session + ".json")).exists():
            raise ValueError(f"Output already exists for {session}")
    output.mkdir(parents=True, exist_ok=True)
    for session, chunks in groups.items():
        target = output / (session + ".aac")
        with target.open("xb") as file:
            for _, path in chunks:
                _, audio = decrypt_chunk(key, path.name, path.read_bytes())
                file.write(audio)
        with (output / (session + ".json")).open("x", encoding="utf-8") as file:
            json.dump([meta for meta, _ in chunks], file, ensure_ascii=False, indent=2)
    return len(groups)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    pull = sub.add_parser("download")
    pull.add_argument("--server", required=True)
    pull.add_argument("--output", type=Path, required=True)
    restore = sub.add_parser("decrypt")
    restore.add_argument("--key-file", type=Path, required=True)
    restore.add_argument("--input", type=Path, required=True)
    restore.add_argument("--output", type=Path, required=True)
    restore.add_argument("--allow-incomplete", action="store_true", help="Recover contiguous chunks of a session that did not finish/upload its end marker")
    args = parser.parse_args()
    try:
        if args.command == "download":
            token = os.environ.get("RECORDER_TOKEN") or getpass.getpass("Server token: ")
            download(args.server, args.output, token)
            print("Ciphertext download complete.")
        else:
            key = base64.b64decode(args.key_file.read_text(encoding="utf-8").strip(), validate=True)
            count = recover(args.input, args.output, key, args.allow_incomplete)
            print(f"Recovered {count} session(s). The output files are plaintext audio.")
    except Exception as exc:
        from cryptography.exceptions import InvalidTag
        message = "Wrong key, renamed chunk, or modified ciphertext" if isinstance(exc, InvalidTag) else str(exc)
        raise SystemExit(message) from None


if __name__ == "__main__":
    main()
