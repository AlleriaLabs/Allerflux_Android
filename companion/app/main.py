import asyncio, json
from contextlib import asynccontextmanager, suppress
from datetime import datetime, timezone
from fastapi import Depends, FastAPI, HTTPException
from sqlalchemy import select, func
from sqlalchemy.orm import Session
from .config import settings
from .database import Base, engine, SessionLocal, get_db
from .models import SessionSnapshot
from .security import require_api_key
from .jellyfin import JellyfinClient

client = JellyfinClient()
poll_task = None

def item_info(s):
    item = s.get("NowPlayingItem") or {}
    trans = s.get("TranscodingInfo") or {}
    state = s.get("PlayState") or {}
    if state.get("IsPaused"):
        status = "paused"
    elif item:
        status = "playing"
    else:
        status = "idle"
    return {
        "session_id": s.get("Id", "unknown"),
        "user_id": s.get("UserId"),
        "username": s.get("UserName"),
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
            # Keep the service alive; health endpoint remains available.
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

app = FastAPI(title="Allerflux Companion API", version="0.1.0", lifespan=lifespan)

@app.get("/health")
async def health():
    return {"status": "ok", "service": "allerflux-companion"}

@app.get("/api/v1/overview", dependencies=[Depends(require_api_key)])
async def overview(db: Session = Depends(get_db)):
    try:
        users = await client.users()
        sessions = await client.sessions()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Jellyfin unavailable: {type(exc).__name__}")
    playing = [s for s in sessions if (s.get("NowPlayingItem") and not (s.get("PlayState") or {}).get("IsPaused"))]
    paused = [s for s in sessions if s.get("NowPlayingItem") and (s.get("PlayState") or {}).get("IsPaused")]
    return {
        "server_connected": True,
        "registered_users": len(users),
        "active_sessions": len(sessions),
        "currently_playing": len(playing),
        "paused": len(paused),
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "history_mode": "snapshot_estimates",
    }

@app.get("/api/v1/sessions", dependencies=[Depends(require_api_key)])
async def current_sessions():
    try:
        sessions = await client.sessions()
        return [item_info(s) for s in sessions]
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Jellyfin unavailable: {type(exc).__name__}")

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

@app.get("/api/v1/items/{item_id}/analytics", dependencies=[Depends(require_api_key)])
def item_analytics(item_id: str, db: Session = Depends(get_db)):
    rows = db.scalars(select(SessionSnapshot).where(SessionSnapshot.item_id == item_id)).all()
    viewers = {r.user_id for r in rows if r.user_id}
    return {
        "item_id": item_id,
        "snapshot_count": len(rows),
        "observed_users": len(viewers),
        "observed_states": {
            "playing": sum(1 for r in rows if r.play_state == "playing"),
            "paused": sum(1 for r in rows if r.play_state == "paused"),
        },
        "accuracy_note": "These are periodic observations, not exact play counts, unique playback starts, completion, or watch duration. No watch-time total is reported until reliable playback events are collected.",
    }
