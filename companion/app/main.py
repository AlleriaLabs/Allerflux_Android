import asyncio, json
import httpx
import os
import sqlite3
import tempfile
from contextlib import asynccontextmanager, suppress
from datetime import datetime, timedelta, timezone
from fastapi import Depends, FastAPI, HTTPException, Query, Request, Body
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask
from sqlalchemy.exc import IntegrityError
from sqlalchemy import select, delete, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session
from pydantic import BaseModel, Field
from uuid import uuid4
from .config import settings
from .database import engine, SessionLocal, get_db
from .migrations import run_migrations
from .models import SessionSnapshot, PlaybackEvent, AlertState, AlertEvent, JellyfinServer
from .server_security import encrypt_secret, decrypt_secret
from .security import require_api_key, require_webhook_key
from .events import normalize_playback_event
from .jellyfin import JellyfinClient

client = JellyfinClient()
poll_task = None
last_cleanup_at = None
poll_status = {"last_attempt_at": None, "last_success_at": None, "last_error_type": None}

def item_info(s):
    item = s.get("NowPlayingItem") or {}
    state = s.get("PlayState") or {}
    if state.get("IsPaused"):
        status = "paused"
    elif item:
        status = "playing"
    else:
        status = "idle"

    last_activity_raw = s.get("LastActivityDate") or s.get("LastPlaybackCheckIn")
    last_activity = None
    idle_seconds = None
    if last_activity_raw:
        try:
            last_activity = datetime.fromisoformat(str(last_activity_raw).replace("Z", "+00:00"))
            if last_activity.tzinfo is None:
                last_activity = last_activity.replace(tzinfo=timezone.utc)
            if status == "idle":
                idle_seconds = max(0, int((datetime.now(timezone.utc) - last_activity).total_seconds()))
        except (TypeError, ValueError):
            last_activity = None

    return {
        "session_id": s.get("Id") or "unknown",
        "user_id": s.get("UserId"),
        "username": s.get("UserName"),
        "device_name": s.get("DeviceName"),
        "client_name": s.get("Client"),
        "client_version": s.get("ApplicationVersion"),
        "device_id": s.get("DeviceId"),
        "remote_endpoint": s.get("RemoteEndPoint"),
        "last_activity": last_activity.isoformat() if last_activity else None,
        "idle_seconds": idle_seconds,
        "item_id": item.get("Id"),
        "item_name": item.get("Name"),
        "media_type": item.get("Type"),
        "play_state": status,
        "position_ticks": int(state.get("PositionTicks") or 0),
        "raw_json": json.dumps(s, separators=(",", ":")),
    }


def change_alert_state(alert_key: str, kind: str, title: str, message: str, active: bool):
    db = SessionLocal()
    try:
        now = datetime.now(timezone.utc)
        state = db.get(AlertState, alert_key)
        if state is None:
            state = AlertState(alert_key=alert_key, is_active=False, updated_at=now)
            db.add(state)
            db.flush()
        if active:
            if state.is_active:
                return None
            state.is_active = True
            cooldown = max(0, settings.alert_cooldown_minutes)
            last_triggered = state.last_triggered_at
            if last_triggered and last_triggered.tzinfo is None:
                last_triggered = last_triggered.replace(tzinfo=timezone.utc)
            if last_triggered and now - last_triggered < timedelta(minutes=cooldown):
                state.updated_at = now
                db.commit()
                return None
            state.last_triggered_at = now
            status = "active"
        else:
            if not state.is_active:
                return None
            state.is_active = False
            status = "resolved"
        state.updated_at = now
        event = AlertEvent(alert_key=alert_key, kind=kind, title=title, message=message,
            status=status, created_at=now)
        db.add(event)
        db.commit()
        return {
            "alert_key": alert_key, "kind": kind, "title": title,
            "message": message, "status": status, "created_at": now.isoformat()
        }
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


async def notify_alert(event: dict | None):
    if not event or not settings.alert_webhook_url:
        return
    try:
        async with httpx.AsyncClient(timeout=8) as client:
            await client.post(settings.alert_webhook_url, json=event)
    except Exception:
        # Alert history remains persisted even when an external notifier is unavailable.
        pass


async def poll_once():
    global last_cleanup_at
    if not settings.jellyfin_api_key:
        poll_status["last_error_type"] = "JellyfinApiKeyNotConfigured"
        return
    poll_status["last_attempt_at"] = datetime.now(timezone.utc).isoformat()
    sessions = await client.sessions()
    await notify_alert(change_alert_state("server:unreachable", "server_unreachable",
        "Jellyfin connection restored", "The companion can reach the Jellyfin server again.", False))

    current_idle_alerts = set()
    db = SessionLocal()
    try:
        now = datetime.now(timezone.utc)
        for s in sessions:
            data = item_info(s)
            if data["play_state"] == "idle" and data["idle_seconds"] is not None:
                if data["idle_seconds"] >= max(60, settings.idle_alert_threshold_seconds):
                    alert_key = "idle:" + data["session_id"]
                    current_idle_alerts.add(alert_key)
                    message = (f'{data["username"] or "A user"} has an idle session on '
                        f'{data["device_name"] or "an unknown device"} for {data["idle_seconds"]} seconds.')
                    await notify_alert(change_alert_state(alert_key, "idle_session",
                        "Idle Jellyfin session", message, True))
            if not data["item_id"]:
                continue
            db.add(SessionSnapshot(
                jellyfin_session_id=data["session_id"], user_id=data["user_id"],
                username=data["username"], item_id=data["item_id"],
                item_name=data["item_name"], media_type=data["media_type"],
                play_state=data["play_state"], position_ticks=data["position_ticks"],
                captured_at=now, raw_json=data["raw_json"]))
        db.commit()
        if last_cleanup_at is None or now - last_cleanup_at >= timedelta(hours=24):
            snapshot_cutoff = now - timedelta(days=max(30, settings.snapshot_retention_days))
            event_cutoff = now - timedelta(days=max(30, settings.event_retention_days))
            db.execute(delete(SessionSnapshot).where(SessionSnapshot.captured_at < snapshot_cutoff))
            db.execute(delete(PlaybackEvent).where(PlaybackEvent.occurred_at < event_cutoff))
            db.commit()
            last_cleanup_at = now
        poll_status["last_success_at"] = now.isoformat()
        poll_status["last_error_type"] = None
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()

    state_db = SessionLocal()
    try:
        active_idle = state_db.scalars(select(AlertState).where(
            AlertState.is_active == True, AlertState.alert_key.like("idle:%"))).all()
        stale_keys = [row.alert_key for row in active_idle if row.alert_key not in current_idle_alerts]
    finally:
        state_db.close()
    for alert_key in stale_keys:
        await notify_alert(change_alert_state(alert_key, "idle_session",
            "Idle session cleared", "The session is no longer above the configured idle threshold.", False))


async def poll_loop():
    while True:
        try:
            await poll_once()
        except Exception as exc:
            poll_status["last_error_type"] = type(exc).__name__
            event = change_alert_state("server:unreachable", "server_unreachable",
                "Jellyfin server unavailable",
                f"The companion could not poll Jellyfin ({type(exc).__name__}).", True)
            await notify_alert(event)
        await asyncio.sleep(max(10, settings.poll_interval_seconds))

@asynccontextmanager
async def lifespan(app: FastAPI):
    global poll_task
    run_migrations(engine)
    poll_task = asyncio.create_task(poll_loop())
    yield
    poll_task.cancel()
    with suppress(asyncio.CancelledError):
        await poll_task

app = FastAPI(title="Allerflux Companion API", version="0.2.0", lifespan=lifespan)

class ServerCreate(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    url: str = Field(min_length=8, max_length=1000)
    api_key: str = Field(min_length=1, max_length=2048)

class ServerUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=120)
    url: str | None = Field(default=None, min_length=8, max_length=1000)
    api_key: str | None = Field(default=None, min_length=1, max_length=2048)
    is_enabled: bool | None = None

def server_public(row: JellyfinServer):
    return {"id": row.id, "name": row.name, "url": row.url,
        "is_default": row.is_default, "is_enabled": row.is_enabled,
        "created_at": row.created_at.isoformat() if row.created_at else None}

@app.get("/api/v1/servers", dependencies=[Depends(require_api_key)])
def list_servers(db: Session = Depends(get_db)):
    rows = db.scalars(select(JellyfinServer).order_by(JellyfinServer.is_default.desc(), JellyfinServer.name)).all()
    return {"servers": [server_public(row) for row in rows]}

@app.post("/api/v1/servers", dependencies=[Depends(require_api_key)], status_code=201)
async def create_server(payload: ServerCreate, db: Session = Depends(get_db)):
    try:
        secret = encrypt_secret(payload.api_key)
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc))
    url = payload.url.strip().rstrip("/")
    if not url.startswith(("http://", "https://")):
        raise HTTPException(status_code=422, detail="Server URL must use http:// or https://")
    row = JellyfinServer(id=str(uuid4()), name=payload.name.strip(), url=url,
        encrypted_api_key=secret, is_default=False, is_enabled=True)
    db.add(row)
    db.commit()
    db.refresh(row)
    return server_public(row)

@app.put("/api/v1/servers/{server_id}", dependencies=[Depends(require_api_key)])
def update_server(server_id: str, payload: ServerUpdate, db: Session = Depends(get_db)):
    row = db.get(JellyfinServer, server_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Server not found")
    if payload.name is not None: row.name = payload.name.strip()
    if payload.url is not None:
        url = payload.url.strip().rstrip("/")
        if not url.startswith(("http://", "https://")):
            raise HTTPException(status_code=422, detail="Server URL must use http:// or https://")
        row.url = url
    if payload.api_key is not None:
        try: row.encrypted_api_key = encrypt_secret(payload.api_key)
        except RuntimeError as exc: raise HTTPException(status_code=503, detail=str(exc))
    if payload.is_enabled is not None: row.is_enabled = payload.is_enabled
    row.updated_at = datetime.now(timezone.utc)
    db.commit()
    return server_public(row)

@app.delete("/api/v1/servers/{server_id}", dependencies=[Depends(require_api_key)])
def delete_server(server_id: str, confirm: bool = Query(default=False), db: Session = Depends(get_db)):
    row = db.get(JellyfinServer, server_id)
    if row is None: raise HTTPException(status_code=404, detail="Server not found")
    if not confirm: raise HTTPException(status_code=400, detail="Pass confirm=true to remove this server")
    if row.is_default: raise HTTPException(status_code=400, detail="The default server cannot be removed")
    db.delete(row)
    db.commit()
    return {"deleted": True, "id": server_id, "history_preserved": True}

@app.post("/api/v1/servers/{server_id}/test", dependencies=[Depends(require_api_key)])
async def test_server(server_id: str, db: Session = Depends(get_db)):
    row = db.get(JellyfinServer, server_id)
    if row is None: raise HTTPException(status_code=404, detail="Server not found")
    try:
        token = decrypt_secret(row.encrypted_api_key)
        async with httpx.AsyncClient(timeout=12) as session:
            response = await session.get(row.url + "/System/Info",
                headers={"Authorization": f'MediaBrowser Token="{token}"'})
            response.raise_for_status()
            info = response.json()
        return {"connected": True, "server_name": info.get("ServerName"),
            "version": info.get("Version"), "id": row.id}
    except Exception as exc:
        return {"connected": False, "error": type(exc).__name__, "id": row.id}





@app.get("/health")
async def health():
    return {"status": "ok", "service": "allerflux-companion"}

@app.post("/api/v1/webhooks/jellyfin", dependencies=[Depends(require_webhook_key)])
async def jellyfin_webhook(request: Request, db: Session = Depends(get_db)):
    try:
        payload = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Webhook body must be valid JSON")
    try:
        event = normalize_playback_event(payload)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc))

    existing = db.scalar(select(PlaybackEvent).where(PlaybackEvent.event_key == event["event_key"]))
    if existing:
        return {"accepted": True, "duplicate": True, "event_type": existing.event_type}

    record = PlaybackEvent(**event)
    db.add(record)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        existing = db.scalar(select(PlaybackEvent).where(PlaybackEvent.event_key == event["event_key"]))
        if existing:
            return {"accepted": True, "duplicate": True, "event_type": existing.event_type}
        raise HTTPException(status_code=500, detail="Unable to persist playback event")
    return {"accepted": True, "duplicate": False, "event_type": record.event_type}



@app.get("/api/v1/alerts/summary", dependencies=[Depends(require_api_key)])
def alerts_summary(db: Session = Depends(get_db)):
    active = db.scalars(select(AlertState).where(AlertState.is_active == True)).all()
    total = db.query(AlertEvent).count()
    return {
        "active_alerts": len(active),
        "total_events": total,
        "idle_threshold_seconds": max(60, settings.idle_alert_threshold_seconds),
        "cooldown_minutes": max(0, settings.alert_cooldown_minutes),
        "outbound_notifications_enabled": bool(settings.alert_webhook_url),
    }


@app.get("/api/v1/alerts/history", dependencies=[Depends(require_api_key)])
def alerts_history(
    limit: int = Query(default=100, ge=1, le=500),
    db: Session = Depends(get_db),
):
    rows = db.scalars(select(AlertEvent).order_by(AlertEvent.created_at.desc()).limit(limit)).all()
    return [{
        "alert_key": row.alert_key, "kind": row.kind, "title": row.title,
        "message": row.message, "status": row.status, "created_at": row.created_at.isoformat()
    } for row in rows]


@app.get("/api/v1/events/summary", dependencies=[Depends(require_api_key)])
def playback_events_summary(
    days: int = Query(default=30, ge=1, le=365),
    db: Session = Depends(get_db),
):
    since = datetime.now(timezone.utc) - timedelta(days=days)
    rows = db.scalars(select(PlaybackEvent).where(PlaybackEvent.occurred_at >= since)).all()
    starts = [row for row in rows if row.event_type == "PlaybackStart"]
    stops = [row for row in rows if row.event_type == "PlaybackStop"]
    completions = [row for row in stops if row.played_to_completion is True]
    return {
        "days": days,
        "event_count": len(rows),
        "playback_starts": len(starts),
        "playback_stops": len(stops),
        "completed_stops": len(completions),
        "distinct_users": len({row.user_id for row in rows}),
        "distinct_titles": len({row.item_id for row in rows}),
        "source": "jellyfin_webhook_events",
        "accuracy_note": "Counts are based on received Jellyfin webhook events. Completion is reported only when the webhook explicitly supplies PlayedToCompletion=true. Watch duration is not inferred.",
    }


@app.get("/api/v1/events/recent", dependencies=[Depends(require_api_key)])
def recent_playback_events(
    limit: int = Query(default=100, ge=1, le=500),
    db: Session = Depends(get_db),
):
    rows = db.scalars(select(PlaybackEvent).order_by(PlaybackEvent.occurred_at.desc()).limit(limit)).all()
    return [{
        "event_type": row.event_type,
        "occurred_at": row.occurred_at.isoformat(),
        "user_id": row.user_id,
        "username": row.username,
        "item_id": row.item_id,
        "item_name": row.item_name,
        "media_type": row.media_type,
        "position_ticks": row.position_ticks,
        "runtime_ticks": row.runtime_ticks,
        "played_to_completion": row.played_to_completion,
        "play_method": row.play_method,
        "device_name": row.device_name,
        "client_name": row.client_name,
        "source": "jellyfin_webhook_event",
    } for row in rows]


@app.get("/api/v1/diagnostics", dependencies=[Depends(require_api_key)])
def companion_diagnostics(db: Session = Depends(get_db)):
    database_connected = False
    try:
        db.execute(text("SELECT 1"))
        database_connected = True
    except Exception:
        database_connected = False
    return {
        "database_connected": database_connected,
        "jellyfin_polling_enabled": bool(settings.jellyfin_api_key),
        "poll_interval_seconds": max(10, settings.poll_interval_seconds),
        "last_poll_attempt_at": poll_status["last_attempt_at"],
        "last_poll_success_at": poll_status["last_success_at"],
        "last_poll_error_type": poll_status["last_error_type"],
        "snapshot_retention_days": max(30, settings.snapshot_retention_days),
        "event_retention_days": max(30, settings.event_retention_days),
    }


@app.get("/api/v1/admin/backup", dependencies=[Depends(require_api_key)])
def download_database_backup():
    database_url = make_url(settings.database_url)
    if database_url.get_backend_name() != "sqlite":
        raise HTTPException(status_code=501, detail="Automated backup currently supports SQLite databases only")
    database_path = database_url.database
    if not database_path or not os.path.isfile(database_path):
        raise HTTPException(status_code=404, detail="Companion database file was not found")
    handle = tempfile.NamedTemporaryFile(prefix="allerflux-backup-", suffix=".sqlite3", delete=False)
    backup_path = handle.name
    handle.close()
    source = sqlite3.connect(database_path)
    destination = sqlite3.connect(backup_path)
    try:
        source.backup(destination)
    except Exception:
        os.unlink(backup_path)
        raise HTTPException(status_code=500, detail="Unable to create a consistent SQLite backup")
    finally:
        source.close()
        destination.close()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    return FileResponse(
        backup_path,
        media_type="application/vnd.sqlite3",
        filename=f"allerflux-backup-{stamp}.sqlite3",
        background=BackgroundTask(os.unlink, backup_path),
    )


@app.get("/api/v1/overview", dependencies=[Depends(require_api_key)])
async def overview():
    try:
        users = await client.users()
        sessions = await client.sessions()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Jellyfin unavailable: {type(exc).__name__}")
    playing = [s for s in sessions if s.get("NowPlayingItem") and not (s.get("PlayState") or {}).get("IsPaused")]
    paused = [s for s in sessions if s.get("NowPlayingItem") and (s.get("PlayState") or {}).get("IsPaused")]
    return {
        "server_connected": True, "registered_users": len(users),
        "active_sessions": len(sessions), "currently_playing": len(playing),
        "paused": len(paused), "captured_at": datetime.now(timezone.utc).isoformat(),
        "history_mode": "snapshot_estimates",
    }

@app.get("/api/v1/sessions", dependencies=[Depends(require_api_key)])
async def current_sessions():
    try:
        return [item_info(s) for s in await client.sessions()]
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Jellyfin unavailable: {type(exc).__name__}")

@app.get("/api/v1/library", dependencies=[Depends(require_api_key)])
async def library():
    try:
        counts = await client.item_counts()
        return {
            "movie_count": int(counts.get("MovieCount") or 0),
            "series_count": int(counts.get("SeriesCount") or 0),
            "episode_count": int(counts.get("EpisodeCount") or 0),
            "song_count": int(counts.get("SongCount") or 0),
            "album_count": int(counts.get("AlbumCount") or 0),
            "artist_count": int(counts.get("ArtistCount") or 0),
            "boxset_count": int(counts.get("BoxSetCount") or 0),
            "captured_at": datetime.now(timezone.utc).isoformat(),
        }
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Jellyfin library unavailable: {type(exc).__name__}")

@app.get("/api/v1/history", dependencies=[Depends(require_api_key)])
def history(limit: int = 100, db: Session = Depends(get_db)):
    limit = min(max(limit, 1), 500)
    rows = db.scalars(select(SessionSnapshot).order_by(SessionSnapshot.captured_at.desc()).limit(limit)).all()
    return [{
        "session_id": r.jellyfin_session_id, "user": r.username, "item_id": r.item_id,
        "item_name": r.item_name, "media_type": r.media_type, "state": r.play_state,
        "position_ticks": r.position_ticks, "captured_at": r.captured_at.isoformat(),
        "record_type": "periodic_snapshot_not_play_event",
    } for r in rows]

@app.get("/api/v1/reports/summary", dependencies=[Depends(require_api_key)])
def reports_summary(
    days: int = Query(default=30, ge=1, le=365),
    group_by: str = Query(default="day", pattern="^(day|week|month)$"),
    db: Session = Depends(get_db),
):
    today = datetime.now(timezone.utc).date()
    first_day = today - timedelta(days=days - 1)
    since = datetime(first_day.year, first_day.month, first_day.day, tzinfo=timezone.utc)
    rows = db.scalars(select(SessionSnapshot).where(SessionSnapshot.captured_at >= since)).all()

    def bucket_start(day):
        if group_by == "week":
            return day - timedelta(days=day.weekday())
        if group_by == "month":
            return day.replace(day=1)
        return day

    grouped = {}
    users = set()
    titles = set()
    for row in rows:
        period = bucket_start(row.captured_at.date())
        key = period.isoformat()
        bucket = grouped.setdefault(key, {
            "date": key, "snapshots": 0, "playing": 0, "paused": 0,
            "users": set(), "titles": set()
        })
        bucket["snapshots"] += 1
        if row.play_state == "playing": bucket["playing"] += 1
        if row.play_state == "paused": bucket["paused"] += 1
        if row.user_id:
            users.add(row.user_id)
            bucket["users"].add(row.user_id)
        if row.item_id:
            titles.add(row.item_id)
            bucket["titles"].add(row.item_id)

    periods = []
    cursor = bucket_start(first_day)
    last_period = bucket_start(today)
    while cursor <= last_period:
        key = cursor.isoformat()
        bucket = grouped.get(key, {
            "date": key, "snapshots": 0, "playing": 0, "paused": 0,
            "users": set(), "titles": set()
        })
        periods.append({
            "date": key, "snapshots": bucket["snapshots"], "playing": bucket["playing"],
            "paused": bucket["paused"], "unique_users": len(bucket["users"]),
            "unique_titles": len(bucket["titles"])
        })
        if group_by == "week":
            cursor += timedelta(days=7)
        elif group_by == "month":
            cursor = (cursor.replace(day=28) + timedelta(days=4)).replace(day=1)
        else:
            cursor += timedelta(days=1)

    return {
        "days": days, "group_by": group_by, "snapshot_count": len(rows),
        "observed_users": len(users), "observed_titles": len(titles),
        "playing_samples": sum(1 for row in rows if row.play_state == "playing"),
        "paused_samples": sum(1 for row in rows if row.play_state == "paused"),
        "daily": periods,
        "accuracy_note": "Periodic session observations only; not exact plays, completion, or watch duration.",
    }


@app.get("/api/v1/reports/compare", dependencies=[Depends(require_api_key)])
def reports_compare(
    days: int = Query(default=30, ge=1, le=365),
    db: Session = Depends(get_db),
):
    now = datetime.now(timezone.utc)
    current_start = now - timedelta(days=days)
    previous_start = current_start - timedelta(days=days)
    current_snapshots = db.scalars(select(SessionSnapshot).where(
        SessionSnapshot.captured_at >= current_start,
        SessionSnapshot.captured_at < now
    )).all()
    previous_snapshots = db.scalars(select(SessionSnapshot).where(
        SessionSnapshot.captured_at >= previous_start,
        SessionSnapshot.captured_at < current_start
    )).all()
    current_events = db.scalars(select(PlaybackEvent).where(
        PlaybackEvent.occurred_at >= current_start,
        PlaybackEvent.occurred_at < now
    )).all()
    previous_events = db.scalars(select(PlaybackEvent).where(
        PlaybackEvent.occurred_at >= previous_start,
        PlaybackEvent.occurred_at < current_start
    )).all()

    def snapshot_metrics(rows):
        return {
            "snapshot_count": len(rows),
            "observed_users": len({row.user_id for row in rows if row.user_id}),
            "observed_titles": len({row.item_id for row in rows if row.item_id}),
            "playing_samples": sum(1 for row in rows if row.play_state == "playing"),
        }

    def event_metrics(rows):
        return {
            "playback_starts": sum(1 for row in rows if row.event_type == "PlaybackStart"),
            "playback_stops": sum(1 for row in rows if row.event_type == "PlaybackStop"),
            "completed_stops": sum(1 for row in rows if row.event_type == "PlaybackStop" and row.played_to_completion is True),
        }

    current = {**snapshot_metrics(current_snapshots), **event_metrics(current_events)}
    previous = {**snapshot_metrics(previous_snapshots), **event_metrics(previous_events)}
    metrics = {}
    for name, current_value in current.items():
        previous_value = previous[name]
        delta = current_value - previous_value
        metrics[name] = {
            "current": current_value, "previous": previous_value, "delta": delta,
            "change_percent": round(delta * 100 / previous_value, 1) if previous_value else None,
        }
    return {
        "days": days,
        "current_period_start": current_start.isoformat(),
        "previous_period_start": previous_start.isoformat(),
        "metrics": metrics,
        "accuracy_note": "Snapshot and webhook event metrics are compared separately; neither is a watch-duration estimate.",
    }


@app.get("/api/v1/reports/top-titles", dependencies=[Depends(require_api_key)])
def reports_top_titles(
    days: int = Query(default=30, ge=1, le=365),
    limit: int = Query(default=10, ge=1, le=50),
    db: Session = Depends(get_db),
):
    since = datetime.now(timezone.utc) - timedelta(days=days)
    rows = db.scalars(select(SessionSnapshot).where(SessionSnapshot.captured_at >= since)).all()
    grouped = {}
    for row in rows:
        if not row.item_id:
            continue
        item = grouped.setdefault(row.item_id, {
            "item_id": row.item_id, "item_name": row.item_name or "Unknown title",
            "media_type": row.media_type or "Unknown", "snapshot_count": 0,
            "playing_samples": 0, "paused_samples": 0, "users": set(),
        })
        item["snapshot_count"] += 1
        if row.play_state == "playing":
            item["playing_samples"] += 1
        elif row.play_state == "paused":
            item["paused_samples"] += 1
        if row.user_id:
            item["users"].add(row.user_id)
    ranked = sorted(grouped.values(), key=lambda item: item["snapshot_count"], reverse=True)[:limit]
    items = []
    for item in ranked:
        observed_users = len(item.pop("users"))
        items.append({**item, "observed_users": observed_users})
    return {
        "days": days,
        "items": items,
        "ranking_basis": "snapshot_count",
        "accuracy_note": "Ranked by periodic observations, not exact plays, completion, or watch duration.",
    }


@app.get("/api/v1/reports/top-users", dependencies=[Depends(require_api_key)])
def reports_top_users(
    days: int = Query(default=30, ge=1, le=365),
    limit: int = Query(default=20, ge=1, le=100),
    db: Session = Depends(get_db),
):
    since = datetime.now(timezone.utc) - timedelta(days=days)
    rows = db.scalars(select(SessionSnapshot).where(SessionSnapshot.captured_at >= since)).all()
    grouped = {}
    for row in rows:
        if not row.user_id:
            continue
        user = grouped.setdefault(row.user_id, {
            "user_id": row.user_id, "username": row.username or "Unknown user",
            "snapshot_count": 0, "playing_samples": 0, "paused_samples": 0,
            "titles": set(),
        })
        user["snapshot_count"] += 1
        if row.play_state == "playing":
            user["playing_samples"] += 1
        elif row.play_state == "paused":
            user["paused_samples"] += 1
        if row.item_id:
            user["titles"].add(row.item_id)
    ranked = sorted(grouped.values(), key=lambda user: user["snapshot_count"], reverse=True)[:limit]
    users = []
    for user in ranked:
        observed_titles = len(user.pop("titles"))
        users.append({**user, "observed_titles": observed_titles})
    return {
        "days": days,
        "users": users,
        "ranking_basis": "snapshot_count",
        "accuracy_note": "Ranked by periodic observations, not exact plays or total watch time.",
    }


@app.get("/api/v1/items/{item_id}/analytics", dependencies=[Depends(require_api_key)])
def item_analytics(item_id: str, db: Session = Depends(get_db)):
    rows = db.scalars(select(SessionSnapshot).where(SessionSnapshot.item_id == item_id)).all()
    viewers = {r.user_id for r in rows if r.user_id}
    return {
        "item_id": item_id, "snapshot_count": len(rows), "observed_users": len(viewers),
        "observed_states": {
            "playing": sum(1 for r in rows if r.play_state == "playing"),
            "paused": sum(1 for r in rows if r.play_state == "paused"),
        },
        "accuracy_note": "Periodic observations, not exact play counts, unique playback starts, completion, or watch duration.",
    }
