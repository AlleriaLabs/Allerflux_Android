import secrets
from fastapi import Header, HTTPException
from .config import settings

async def require_api_key(x_allerflux_key: str | None = Header(default=None)):
    expected = settings.allerflux_api_key
    if not expected:
        raise HTTPException(status_code=503, detail="Companion API key is not configured")
    if not x_allerflux_key or not secrets.compare_digest(x_allerflux_key, expected):
        raise HTTPException(status_code=401, detail="Invalid API key")
