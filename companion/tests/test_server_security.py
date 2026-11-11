import base64
import secrets

import pytest

from companion.app import server_security


def test_encrypt_decrypt_round_trip(monkeypatch):
    key = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode("ascii")
    monkeypatch.setattr(server_security.settings, "allerflux_encryption_key", key)
    encrypted = server_security.encrypt_secret("jellyfin-secret-token")
    assert encrypted != "jellyfin-secret-token"
    assert server_security.decrypt_secret(encrypted) == "jellyfin-secret-token"


def test_encryption_requires_persistent_key(monkeypatch):
    monkeypatch.setattr(server_security.settings, "allerflux_encryption_key", "")
    with pytest.raises(RuntimeError, match="not configured"):
        server_security.encrypt_secret("token")


def test_encryption_rejects_wrong_key(monkeypatch):
    key_a = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode("ascii")
    key_b = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode("ascii")
    monkeypatch.setattr(server_security.settings, "allerflux_encryption_key", key_a)
    encrypted = server_security.encrypt_secret("token")
    monkeypatch.setattr(server_security.settings, "allerflux_encryption_key", key_b)
    with pytest.raises(Exception):
        server_security.decrypt_secret(encrypted)
