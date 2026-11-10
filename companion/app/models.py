from datetime import datetime, timezone
from sqlalchemy import DateTime, Integer, String, Text, Index
from sqlalchemy.orm import Mapped, mapped_column
from .database import Base

def utcnow():
    return datetime.now(timezone.utc)

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
