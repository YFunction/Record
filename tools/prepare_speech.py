"""Fetch the pinned official Android JNI dependency before a source build."""
from pathlib import Path
import hashlib
import urllib.request

VERSION = '1.13.8'
SHA256 = '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'
ROOT = Path(__file__).resolve().parents[1]
def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        while block := source.read(65536): value.update(block)
    return value.hexdigest()

def main():
    dest = ROOT / '.tools/speech/sherpa.aar'
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.is_file() and digest(dest) == SHA256:
        print('Pinned sherpa-onnx dependency is ready.'); return
    part = dest.with_suffix('.part')
    try:
        url = f'https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar'
        with urllib.request.urlopen(url, timeout=60) as response, part.open('wb') as output:
            total = 0
            while block := response.read(65536):
                total += len(block)
                if total > 60 * 1024 * 1024: raise ValueError('Dependency exceeds expected size')
                output.write(block)
        if digest(part) != SHA256: raise ValueError('Dependency checksum mismatch')
        part.replace(dest)
        print('Pinned sherpa-onnx dependency is ready.')
    finally:
        part.unlink(missing_ok=True)

if __name__ == '__main__': main()
