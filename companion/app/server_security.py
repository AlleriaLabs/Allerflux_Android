"""AES-GCM encryption for Jellyfin API credentials."""
import base64
import os
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from .config import settings

def _key() -> bytes:
    value = settings.allerflux_encryption_key.strip()
    if not value:
        raise RuntimeError("Server credential encryption is not configured")
    try:
        key = base64.urlsafe_b64decode(value.encode("ascii"))
    except Exception as exc:
        raise RuntimeError("ALLERFLUX_ENCRYPTION_KEY must be URL-safe base64") from exc
    if len(key) != 32:
        raise RuntimeError("ALLERFLUX_ENCRYPTION_KEY must decode to exactly 32 bytes")
    return key

def encrypt_secret(value: str) -> str:
    nonce = os.urandom(12)
    encrypted = AESGCM(_key()).encrypt(nonce, value.encode("utf-8"), b"allerflux-jellyfin-api-key-v1")
    return base64.urlsafe_b64encode(nonce + encrypted).decode("ascii")

def decrypt_secret(value: str) -> str:
    raw = base64.urlsafe_b64decode(value.encode("ascii"))
    return AESGCM(_key()).decrypt(raw[:12], raw[12:], b"allerflux-jellyfin-api-key-v1").decode("utf-8")
