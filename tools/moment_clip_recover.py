"""Decrypt a user-exported Record moment clip into a plaintext AAC file."""
import argparse
import base64
import json
import re
import struct
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAX_CLIP_BYTES = 1024 * 1024 + 4096
SESSION = re.compile(r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\Z")


def decrypt_clip(envelope: bytes, key: bytes):
    if len(key) != 32 or not 32 <= len(envelope) <= MAX_CLIP_BYTES or envelope[:4] != b"RC01":
        raise ValueError("加密片段格式或恢复密钥无效")
    plain = AESGCM(key).decrypt(envelope[4:16], envelope[16:], b"record-moment-clip-v1")
    if len(plain) < 12:
        raise ValueError("片段内容不完整")
    header_len = struct.unpack_from(">I", plain)[0]
    if not 2 <= header_len <= 2048 or 4 + header_len + 7 > len(plain):
        raise ValueError("片段元数据无效")
    metadata = json.loads(plain[4:4 + header_len].decode("utf-8"))
    if (metadata.get("version") != 1 or metadata.get("codec") != "aac-adts"
            or not isinstance(metadata.get("session"), str) or not SESSION.fullmatch(metadata["session"])
            or not all(isinstance(metadata.get(k), int) and metadata[k] >= 0
                       for k in ("markOffsetMs", "startMs", "endMs"))
            or metadata["startMs"] >= metadata["endMs"]):
        raise ValueError("片段元数据无效")
    audio = plain[4 + header_len:]
    if audio[:2] != b"\xff\xf1":
        raise ValueError("AAC 数据无效")
    return metadata, audio


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key-file", type=Path, required=True, help="手机导出的恢复密钥文件")
    parser.add_argument("--input", type=Path, required=True, help=".rcclip 加密片段")
    parser.add_argument("--output", type=Path, required=True, help="输出的明文 .aac 路径；拒绝覆盖")
    args = parser.parse_args()
    try:
        key = base64.b64decode(args.key_file.read_text(encoding="utf-8").strip(), validate=True)
        metadata, audio = decrypt_clip(args.input.read_bytes(), key)
        with args.output.open("xb") as output:
            output.write(audio)
        print(f"已导出 AAC：{metadata['session']}，录音内 {metadata['startMs']}–{metadata['endMs']} 毫秒。")
    except Exception as exc:
        from cryptography.exceptions import InvalidTag
        raise SystemExit("恢复密钥不匹配或片段被篡改" if isinstance(exc, InvalidTag) else str(exc)) from None


if __name__ == "__main__":
    main()
