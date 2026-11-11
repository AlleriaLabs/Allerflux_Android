import unittest

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from companion.app.migrations import run_migrations
from companion.app.models import SessionSnapshot


class SchemaMigrationTests(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine(
            "sqlite://",
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
        )

    def tearDown(self):
        self.engine.dispose()

    def test_migration_preserves_existing_snapshot_rows_and_adds_new_tables(self):
        # Simulate the original deployed database: the snapshot table already
        # contains real history, while newer companion tables do not exist.
        with self.engine.begin() as connection:
            connection.execute(text("""
                CREATE TABLE session_snapshots (
                    id INTEGER PRIMARY KEY,
                    jellyfin_session_id VARCHAR(160) NOT NULL,
                    user_id VARCHAR(160),
                    username VARCHAR(160),
                    item_id VARCHAR(160),
                    item_name VARCHAR(500),
                    media_type VARCHAR(80),
                    play_state VARCHAR(30),
                    position_ticks INTEGER,
                    captured_at DATETIME,
                    raw_json TEXT
                )
            """))
            connection.execute(text("""
                INSERT INTO session_snapshots
                (id, jellyfin_session_id, user_id, username, item_id, item_name,
                 media_type, play_state, position_ticks, captured_at, raw_json)
                VALUES (1, 'session-1', 'user-1', 'Viewer', 'item-1',
                        'Preserved Movie', 'Movie', 'playing', 123, '2026-11-11 00:00:00',
                        '{}')
            """))

        run_migrations(self.engine)

        with Session(self.engine) as db:
            row = db.get(SessionSnapshot, 1)
            self.assertIsNotNone(row)
            self.assertEqual(row.item_name, "Preserved Movie")
            self.assertEqual(row.user_id, "user-1")

        tables = set(inspect(self.engine).get_table_names())
        self.assertTrue({
            "session_snapshots", "playback_events", "alert_states",
            "alert_events", "allerflux_schema_migrations"
        }.issubset(tables))

    def test_migrations_are_idempotent(self):
        run_migrations(self.engine)
        run_migrations(self.engine)
        with self.engine.connect() as connection:
            versions = connection.execute(
                text("SELECT version FROM allerflux_schema_migrations")
            ).scalars().all()
        self.assertEqual(versions, ["0001_initial_schema"])


if __name__ == "__main__":
    unittest.main()
