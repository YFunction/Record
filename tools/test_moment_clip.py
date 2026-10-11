import json
import struct
import unittest

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from moment_clip_recover import decrypt_clip


class ClipRecoveryTest(unittest.TestCase):
    def test_round_trip_and_tamper_rejection(self):
        key = bytes(32)
        metadata = {"version": 1, "session": "10000000-0000-4000-8000-000000000001",
                    "markOffsetMs": 1000, "startMs": 0, "endMs": 2000, "codec": "aac-adts"}
        header = json.dumps(metadata).encode()
        audio = b"\xff\xf1\x60\x40\x00\xff\xfc"
        nonce = bytes(range(12))
        envelope = b"RC01" + nonce + AESGCM(key).encrypt(nonce, struct.pack(">I", len(header)) + header + audio,
                                                       b"record-moment-clip-v1")
        result, recovered = decrypt_clip(envelope, key)
        self.assertEqual(result, metadata)
        self.assertEqual(recovered, audio)
        changed = bytearray(envelope)
        changed[-1] ^= 1
        with self.assertRaises(Exception):
            decrypt_clip(bytes(changed), key)


if __name__ == "__main__":
    unittest.main()
