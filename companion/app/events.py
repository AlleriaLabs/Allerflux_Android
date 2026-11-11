import hashlib
import json
from datetime import datetime, timezone


SUPPORTED_TYPES = {"PlaybackStart", "PlaybackProgress", "PlaybackStop"}


def _optional_int(value):
    if value in (None, ""):
        return None
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return None


def _optional_bool(value):
    if value is None or value == "":
        return None
    if isinstance(value, bool):
        return value
    normalized = str(value).strip().lower()
    if normalized in {"true", "1", "yes"}:
        return True
    if normalized in {"false", "0", "no"}:
        return False
    return None


def normalize_playback_event(payload):
    """Normalize the documented Jellyfin Webhook plugin JSON template payload."""
    if not isinstance(payload, dict):
        raise ValueError("Webhook body must be a JSON object")

    event_type = str(payload.get("NotificationType") or "").strip()
    if event_type not in SUPPORTED_TYPES:
        raise ValueError("Unsupported NotificationType")

    timestamp = payload.get("UtcTimestamp") or payload.get("Timestamp")
    if not timestamp:
        raise ValueError("UtcTimestamp is required")
    try:
        occurred_at = datetime.fromisoformat(str(timestamp).replace("Z", "+00:00"))
        if occurred_at.tzinfo is None:
            occurred_at = occurred_at.replace(tzinfo=timezone.utc)
        occurred_at = occurred_at.astimezone(timezone.utc)
    except (TypeError, ValueError):
        raise ValueError("UtcTimestamp must be an ISO-8601 timestamp")

    item_id = str(payload.get("ItemId") or "").strip()
    user_id = str(payload.get("UserId") or "").strip()
    if not item_id or not user_id:
        raise ValueError("ItemId and UserId are required")

    normalized = {
        "event_type": event_type,
        "occurred_at": occurred_at,
        "user_id": user_id,
        "username": str(payload.get("NotificationUsername") or payload.get("Username") or "Unknown user"),
        "item_id": item_id,
        "item_name": str(payload.get("Name") or "Unknown title"),
        "media_type": str(payload.get("ItemType") or "Unknown"),
        "position_ticks": _optional_int(payload.get("PlaybackPositionTicks")) or 0,
        "runtime_ticks": _optional_int(payload.get("RunTimeTicks")),
        "played_to_completion": _optional_bool(payload.get("PlayedToCompletion")),
        "play_method": str(payload.get("PlayMethod") or ""),
        "device_name": str(payload.get("DeviceName") or ""),
        "device_id": str(payload.get("DeviceId") or ""),
        "client_name": str(payload.get("ClientName") or payload.get("Client") or ""),
    }
    fingerprint_data = {
        key: (value.isoformat() if isinstance(value, datetime) else value)
        for key, value in normalized.items()
    }
    normalized["event_key"] = hashlib.sha256(
        json.dumps(fingerprint_data, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    normalized["raw_json"] = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return normalized
