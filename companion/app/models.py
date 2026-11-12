from datetime import datetime, timezone
from sqlalchemy import Boolean, DateTime, Integer, BigInteger, String, Text, Index
from sqlalchemy.orm import Mapped, mapped_column
from .database import Base

def utcnow():
    return datetime.now(timezone.utc)

class JellyfinServer(Base):
    __tablename__ = "jellyfin_servers"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    url: Mapped[str] = mapped_column(String(1000), nullable=False)
    encrypted_api_key: Mapped[str] = mapped_column(Text, nullable=False)
    is_default: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    is_enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class SessionSnapshot(Base):
    __tablename__ = "session_snapshots"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    jellyfin_session_id: Mapped[str] = mapped_column(String(160), index=True)
    user_id: Mapped[str | None] = mapped_column(String(160), nullable=True, index=True)
    username: Mapped[str | None] = mapped_column(String(160), nullable=True)
    item_id: Mapped[str | None] = mapped_column(String(160), nullable=True, index=True)
    item_name: Mapped[str | None] = mapped_column(String(500), nullable=True)
    media_type: Mapped[str | None] = mapped_column(String(80), nullable=True)
    play_state: Mapped[str] = mapped_column(String(30), default="unknown")
    position_ticks: Mapped[int] = mapped_column(Integer, default=0)
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, index=True)
    raw_json: Mapped[str] = mapped_column(Text, default="{}")

Index("ix_snapshot_item_time", SessionSnapshot.item_id, SessionSnapshot.captured_at)


class PlaybackEvent(Base):
    __tablename__ = "playback_events"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    event_key: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    event_type: Mapped[str] = mapped_column(String(40), index=True)
    user_id: Mapped[str] = mapped_column(String(160), index=True)
    username: Mapped[str] = mapped_column(String(160), default="Unknown user")
    item_id: Mapped[str] = mapped_column(String(160), index=True)
    item_name: Mapped[str] = mapped_column(String(500), default="Unknown title")
    media_type: Mapped[str] = mapped_column(String(80), default="Unknown")
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    position_ticks: Mapped[int] = mapped_column(BigInteger, default=0)
    runtime_ticks: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    played_to_completion: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    play_method: Mapped[str | None] = mapped_column(String(40), nullable=True)
    device_name: Mapped[str | None] = mapped_column(String(200), nullable=True)
    device_id: Mapped[str | None] = mapped_column(String(200), nullable=True)
    client_name: Mapped[str | None] = mapped_column(String(200), nullable=True)
    raw_json: Mapped[str] = mapped_column(Text, default="{}")


Index("ix_playback_event_type_time", PlaybackEvent.event_type, PlaybackEvent.occurred_at)
Index("ix_playback_event_user_time", PlaybackEvent.user_id, PlaybackEvent.occurred_at)
Index("ix_playback_event_item_time", PlaybackEvent.item_id, PlaybackEvent.occurred_at)


class AlertState(Base):
    __tablename__ = "alert_states"
    alert_key: Mapped[str] = mapped_column(String(255), primary_key=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=False)
    last_triggered_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class AlertEvent(Base):
    __tablename__ = "alert_events"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    alert_key: Mapped[str] = mapped_column(String(255), index=True)
    kind: Mapped[str] = mapped_column(String(80), index=True)
    title: Mapped[str] = mapped_column(String(200))
    message: Mapped[str] = mapped_column(String(1000))
    status: Mapped[str] = mapped_column(String(30), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, index=True)


Index("ix_alert_event_kind_time", AlertEvent.kind, AlertEvent.created_at)
