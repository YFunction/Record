"""Download encrypted text revisions and authenticate all of them before export."""
import argparse
import base64
import getpass
import json
import os
from pathlib import Path
from recover import DOCUMENT_NAME, download

MAX_DOCUMENT = 1024 * 1024

def decrypt_document(key: bytes, name: str, envelope: bytes):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    if len(key) != 32 or not DOCUMENT_NAME.fullmatch(name) or not 32 <= len(envelope) <= MAX_DOCUMENT + 32 or envelope[:4] != b'ET01':
        raise ValueError('Invalid encrypted text envelope')
    payload = AESGCM(key).decrypt(envelope[4:16], envelope[16:], ('text-v1:' + name).encode())
    doc = json.loads(payload)
    if doc.get('version') != 1 or doc.get('session') != name[:36] or not isinstance(doc.get('segments'), list) or not isinstance(doc.get('updatedAt'), int):
        raise ValueError('Text metadata does not match filename')
    return doc

def recover_documents(source: Path, output: Path, key: bytes):
    paths = sorted(source.glob('*.enc'))
    if not paths: raise ValueError('No encrypted text revisions found')
    for path in paths:
        if path.stat().st_size > MAX_DOCUMENT + 32: raise ValueError('Text envelope too large')
        decrypt_document(key, path.name, path.read_bytes())
        if (output / (path.stem + '.json')).exists(): raise ValueError('Output revision already exists')
    output.mkdir(parents=True, exist_ok=True)
    for path in paths:
        doc = decrypt_document(key, path.name, path.read_bytes())
        with (output / (path.stem + '.json')).open('x', encoding='utf-8') as dest:
            json.dump(doc, dest, ensure_ascii=False, indent=2)
            dest.write('\n')
    return len(paths)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    fetch = commands.add_parser('download'); fetch.add_argument('--server', required=True); fetch.add_argument('--output', type=Path, required=True)
    decrypt = commands.add_parser('decrypt'); decrypt.add_argument('--key-file', type=Path, required=True); decrypt.add_argument('--input', type=Path, required=True); decrypt.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'download':
            token = os.environ.get('RECORDER_TOKEN') or getpass.getpass('Server token: ')
            download(args.server, args.output, token, documents=True)
            print('Encrypted text revisions downloaded.')
        else:
            key = base64.b64decode(args.key_file.read_text(encoding='utf-8').strip(), validate=True)
            count = recover_documents(args.input, args.output, key)
            print(f'Recovered {count} revision(s). Outputs are plaintext JSON with transcripts and summaries.')
    except Exception as exc:
        from cryptography.exceptions import InvalidTag
        raise SystemExit('Wrong key, renamed text revision, or modified ciphertext' if isinstance(exc, InvalidTag) else str(exc)) from None

if __name__ == '__main__': main()
