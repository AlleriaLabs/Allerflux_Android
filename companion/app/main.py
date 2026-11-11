import asyncio, json
from contextlib import asynccontextmanager, suppress
from datetime import datetime, timedelta, timezone
from fastapi import Depends, FastAPI, HTTPException, Query, Request
from sqlalchemy.exc import IntegrityError
from sqlalchemy import select
from sqlalchemy.orm import Session
from .config import settings
from .database import Base, engine, SessionLocal, get_db
from .models import SessionSnapshot, PlaybackEvent
from .security import require_api_key, require_webhook_key
from .events import normalize_playback_event
from .jellyfin import JellyfinClient

client = JellyfinClient()
poll_task = None

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

async def poll_once():
    if not settings.jellyfin_api_key:
        return
    sessions = await client.sessions()
    db = SessionLocal()
    try:
        now = datetime.now(timezone.utc)
        for s in sessions:
            data = item_info(s)
            if not data["item_id"]:
                continue
            db.add(SessionSnapshot(
                jellyfin_session_id=data["session_id"], user_id=data["user_id"],
                username=data["username"], item_id=data["item_id"],
                item_name=data["item_name"], media_type=data["media_type"],
                play_state=data["play_state"], position_ticks=data["position_ticks"],
                captured_at=now, raw_json=data["raw_json"]))
        db.commit()
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()

async def poll_loop():
    while True:
        try:
            await poll_once()
        except Exception:
            pass
        await asyncio.sleep(max(10, settings.poll_interval_seconds))

@asynccontextmanager
async def lifespan(app: FastAPI):
    global poll_task
    Base.metadata.create_all(bind=engine)
    poll_task = asyncio.create_task(poll_loop())
    yield
    poll_task.cancel()
    with suppress(asyncio.CancelledError):
        await poll_task

app = FastAPI(title="Allerflux Companion API", version="0.2.0", lifespan=lifespan)

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
def reports_summary(days: int = Query(default=30, ge=1, le=365), db: Session = Depends(get_db)):
    today = datetime.now(timezone.utc).date()
    first_day = today - timedelta(days=days - 1)
    since = datetime(first_day.year, first_day.month, first_day.day, tzinfo=timezone.utc)
    rows = db.scalars(select(SessionSnapshot).where(SessionSnapshot.captured_at >= since)).all()
    by_day = {}
    users = set()
    titles = set()
    for row in rows:
        day = row.captured_at.date().isoformat()
        bucket = by_day.setdefault(day, {
            "date": day, "snapshots": 0, "playing": 0, "paused": 0,
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
    # Fill missing dates and expose distinct viewers and titles per day.
    daily = []
    for offset in range(days):
        day = (first_day + timedelta(days=offset)).isoformat()
        bucket = by_day.get(day, {"date": day, "snapshots": 0, "playing": 0, "paused": 0, "users": set(), "titles": set()})
        daily.append({
            "date": day, "snapshots": bucket["snapshots"], "playing": bucket["playing"],
            "paused": bucket["paused"], "unique_users": len(bucket["users"]),
            "unique_titles": len(bucket["titles"])
        })
    return {
        "days": days, "snapshot_count": len(rows), "observed_users": len(users),
        "observed_titles": len(titles),
        "playing_samples": sum(1 for row in rows if row.play_state == "playing"),
        "paused_samples": sum(1 for row in rows if row.play_state == "paused"),
        "daily": daily,
        "accuracy_note": "Periodic session observations only; not exact plays, completion, or watch duration.",
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
