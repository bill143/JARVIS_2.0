"""Key management integration abstraction: local + cloud-KMS-ready interface.

The local provider derives keys via PBKDF2 and encrypts with an XOR-keystream
(stdlib only, deterministic) — sufficient for the abstraction and tests. A cloud
KMS (AWS/GCP/Azure) plugs in behind the same encrypt/decrypt/rotate interface.
"""

from __future__ import annotations

import base64
import hashlib
import os
from abc import ABC, abstractmethod


class KmsProvider(ABC):
    name = "base"

    @abstractmethod
    def encrypt(self, plaintext: str, key_id: str = "default") -> str: ...

    @abstractmethod
    def decrypt(self, ciphertext: str, key_id: str = "default") -> str: ...

    @abstractmethod
    def rotate(self, key_id: str = "default") -> str: ...


class LocalKms(KmsProvider):
    name = "local"

    def __init__(self, master: str | None = None):
        self._master = master or os.environ.get("JARVIS_KMS_MASTER", "local-dev-master-key")
        self._versions: dict[str, int] = {}

    def _keystream(self, key_id: str, length: int, version: int) -> bytes:
        seed = hashlib.pbkdf2_hmac("sha256", f"{self._master}:{key_id}:v{version}".encode(), b"jarvis-kms", 50_000)
        out = bytearray()
        counter = 0
        while len(out) < length:
            out += hashlib.sha256(seed + counter.to_bytes(4, "big")).digest()
            counter += 1
        return bytes(out[:length])

    def encrypt(self, plaintext: str, key_id: str = "default") -> str:
        data = plaintext.encode()
        version = self._versions.get(key_id, 1)
        ks = self._keystream(key_id, len(data), version)
        ct = bytes(a ^ b for a, b in zip(data, ks, strict=False))
        return f"kms:{self.name}:v{version}:" + base64.b64encode(ct).decode()

    def decrypt(self, ciphertext: str, key_id: str = "default") -> str:
        try:
            _, _name, ver, b64 = ciphertext.split(":", 3)
            version = int(ver.lstrip("v"))
        except ValueError:
            raise ValueError("malformed KMS ciphertext") from None
        data = base64.b64decode(b64)
        ks = self._keystream(key_id, len(data), version)  # decrypt under the sealed version
        return bytes(a ^ b for a, b in zip(data, ks, strict=False)).decode(errors="replace")

    def rotate(self, key_id: str = "default") -> str:
        self._versions[key_id] = self._versions.get(key_id, 1) + 1
        return f"v{self._versions[key_id]}"


def get_kms(provider: str = "local") -> KmsProvider:
    # Seam for cloud KMS (AWS/GCP/Azure); local provider is the default.
    return LocalKms()
