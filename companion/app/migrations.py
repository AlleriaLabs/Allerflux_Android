"""Small, additive schema migration runner for the companion.

The first migration adopts the existing SQLAlchemy schema without dropping or
rewriting existing rows. Future schema changes should be added as new numbered
steps in MIGRATIONS and must remain additive/backward compatible.
"""
from datetime import datetime, timezone

from sqlalchemy import text
from sqlalchemy.engine import Engine

from .database import Base\nfrom .models import JellyfinServer


def _initial_schema(engine: Engine) -> None:
    # create_all is intentionally additive: existing tables and their data are
    # retained, while newly introduced tables and indexes are created.
    Base.metadata.create_all(bind=engine)


MIGRATIONS = (
    ("0001_initial_schema", _initial_schema),
)


def run_migrations(engine: Engine) -> None:
    """Apply pending schema steps once and ensure metadata tables exist."""
    with engine.begin() as connection:
        connection.execute(text(
            "CREATE TABLE IF NOT EXISTS allerflux_schema_migrations ("
            "version VARCHAR(100) PRIMARY KEY, "
            "applied_at VARCHAR(40) NOT NULL)"
        ))
        applied = {
            row[0] for row in connection.execute(
                text("SELECT version FROM allerflux_schema_migrations")
            ).fetchall()
        }

    for version, migration in MIGRATIONS:
        if version in applied:
            # create_all also repairs missing tables/indexes without altering
            # existing data or replacing the user's database.
            Base.metadata.create_all(bind=engine)
            continue
        migration(engine)
        with engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO allerflux_schema_migrations "
                    "(version, applied_at) VALUES (:version, :applied_at)"
                ),
                {
                    "version": version,
                    "applied_at": datetime.now(timezone.utc).isoformat(),
                },
            )
