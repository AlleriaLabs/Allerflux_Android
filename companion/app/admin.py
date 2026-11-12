import httpx
from fastapi import APIRouter, Depends, HTTPException, Query
from .config import settings
from .security import require_api_key

router = APIRouter(prefix="/api/v1", dependencies=[Depends(require_api_key)])


def _headers():
    return {"Authorization": f'MediaBrowser Token="{settings.jellyfin_api_key}"', "Accept": "application/json"}


async def _request(method: str, path: str, *, params=None):
    if not settings.jellyfin_api_key:
        raise HTTPException(status_code=503, detail="Jellyfin API key is not configured")
    url = settings.jellyfin_url.rstrip("/") + path
    try:
        async with httpx.AsyncClient(timeout=15) as session:
            response = await session.request(method, url, headers=_headers(), params=params)
            response.raise_for_status()
            if response.status_code == 204 or not response.content:
                return {}
            try:
                return response.json()
            except ValueError:
                return {}
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        detail = "Jellyfin rejected the administration request"
        if status == 401 or status == 403:
            detail = "Jellyfin denied this API key or permission"
        elif status == 404:
            detail = "The requested Jellyfin resource was not found"
        raise HTTPException(status_code=502, detail=detail)
    except httpx.RequestError as exc:
        raise HTTPException(status_code=502, detail=f"Jellyfin unavailable: {type(exc).__name__}")


@router.get("/users")
async def admin_users():
    rows = await _request("GET", "/Users")
    if not isinstance(rows, list):
        raise HTTPException(status_code=502, detail="Unexpected Jellyfin users response")
    return [{
        "id": row.get("Id"),
        "name": row.get("Name") or "Unknown user",
        "has_password": bool(row.get("HasPassword")),
        "has_configured_password": bool(row.get("HasConfiguredPassword")),
        "last_login": row.get("LastLoginDate"),
        "last_activity": row.get("LastActivityDate"),
        "is_administrator": bool((row.get("Policy") or {}).get("IsAdministrator")),
        "is_disabled": bool((row.get("Policy") or {}).get("IsDisabled")),
        "enable_remote_access": (row.get("Policy") or {}).get("EnableRemoteAccess"),
    } for row in rows]


@router.get("/devices")
async def admin_devices():
    payload = await _request("GET", "/Devices")
    rows = payload.get("Items", []) if isinstance(payload, dict) else []
    return [{
        "id": row.get("Id"),
        "name": row.get("Name") or "Unknown device",
        "app_name": row.get("AppName"),
        "app_version": row.get("AppVersion"),
        "last_user_name": row.get("LastUserName"),
        "last_user_id": row.get("LastUserId"),
        "date_last_activity": row.get("DateLastActivity"),
    } for row in rows]


@router.get("/system")
async def admin_system():
    info = await _request("GET", "/System/Info")
    public = await _request("GET", "/System/Info/Public")
    return {
        "server_name": info.get("ServerName") or public.get("ServerName"),
        "version": info.get("Version"),
        "operating_system": info.get("OperatingSystem"),
        "architecture": info.get("SystemArchitecture"),
        "id": info.get("Id"),
        "startup_time": info.get("StartupTime"),
        "websocket_port": info.get("WebSocketPortNumber"),
        "can_self_restart": bool(info.get("CanSelfRestart")),
        "can_self_update": bool(info.get("CanSelfUpdate")),
    }


@router.get("/tasks")
async def admin_tasks():
    rows = await _request("GET", "/ScheduledTasks")
    if not isinstance(rows, list):
        raise HTTPException(status_code=502, detail="Unexpected Jellyfin scheduled tasks response")
    return [{
        "id": row.get("Id"),
        "name": row.get("Name") or row.get("Key") or "Scheduled task",
        "description": row.get("Description"),
        "state": row.get("State") or "Idle",
        "is_hidden": bool(row.get("IsHidden")),
        "last_execution": row.get("LastExecutionResult"),
        "triggers": row.get("Triggers") or [],
    } for row in rows]


@router.post("/tasks/{task_id}/run")
async def run_admin_task(task_id: str):
    if not task_id.strip() or len(task_id) > 200:
        raise HTTPException(status_code=422, detail="Invalid task ID")
    await _request("POST", f"/ScheduledTasks/Running/{task_id}")
    return {"accepted": True, "task_id": task_id}


@router.post("/library/refresh")
async def refresh_library():
    await _request("POST", "/Library/Refresh")
    return {"accepted": True, "message": "Jellyfin library refresh requested"}
