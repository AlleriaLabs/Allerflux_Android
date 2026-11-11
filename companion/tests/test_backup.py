import os
import sqlite3
import tempfile
import unittest
import shutil
from datetime import datetime, timezone

from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

from companion.app.database import Base
from companion.app.models import SessionSnapshot, PlaybackEvent, AlertState, AlertEvent

from fastapi import HTTPException

import companion.app.main as main


class DatabaseBackupTests(unittest.TestCase):
    def setUp(self):
        self.original_url = main.settings.database_url
        self.temp_dir = tempfile.TemporaryDirectory()
        self.database_path = os.path.join(self.temp_dir.name, "allerflux.db")
        connection = sqlite3.connect(self.database_path)
        connection.execute("CREATE TABLE backup_fixture (value TEXT)")
        connection.execute("INSERT INTO backup_fixture(value) VALUES ('preserved')")
        connection.commit()
        connection.close()
        main.settings.database_url = "sqlite:///" + self.database_path

    def tearDown(self):
        main.settings.database_url = self.original_url
        self.temp_dir.cleanup()

    def test_backup_is_a_consistent_sqlite_copy(self):
        response = main.download_database_backup()
        backup_path = response.path
        try:
            connection = sqlite3.connect(backup_path)
            value = connection.execute("SELECT value FROM backup_fixture").fetchone()[0]
            connection.close()
            self.assertEqual(value, "preserved")
            self.assertTrue(os.path.getsize(backup_path) > 0)
        finally:
            if os.path.exists(backup_path):
                os.unlink(backup_path)

    def test_backup_can_be_restored_with_snapshots_events_and_alerts(self):
        source_path = os.path.join(self.temp_dir.name, "source-with-history.sqlite3")
        source_engine = create_engine("sqlite:///" + source_path)
        Base.metadata.create_all(source_engine)
        with Session(source_engine) as db:
            db.add(SessionSnapshot(
                jellyfin_session_id="session-restore", user_id="viewer-1",
                username="Viewer", item_id="movie-1", item_name="Restore Movie",
                media_type="Movie", play_state="playing", position_ticks=100,
                captured_at=datetime.now(timezone.utc), raw_json="{}",
            ))
            db.add(PlaybackEvent(
                event_key="restore-event", event_type="PlaybackStart",
                user_id="viewer-1", username="Viewer", item_id="movie-1",
                item_name="Restore Movie", media_type="Movie",
                occurred_at=datetime.now(timezone.utc), raw_json="{}",
            ))
            db.add(AlertState(alert_key="server:restore", is_active=True))
            db.add(AlertEvent(
                alert_key="server:restore", kind="server_unreachable",
                title="Test alert", message="Preserve alert history",
                status="active", created_at=datetime.now(timezone.utc),
            ))
            db.commit()

        main.settings.database_url = "sqlite:///" + source_path
        response = main.download_database_backup()
        backup_path = response.path
        restored_path = os.path.join(self.temp_dir.name, "restored.sqlite3")
        try:
            shutil.copyfile(backup_path, restored_path)
            restored_engine = create_engine("sqlite:///" + restored_path)
            with Session(restored_engine) as restored:
                self.assertEqual(len(restored.scalars(select(SessionSnapshot)).all()), 1)
                self.assertEqual(len(restored.scalars(select(PlaybackEvent)).all()), 1)
                self.assertIsNotNone(restored.get(AlertState, "server:restore"))
                self.assertEqual(len(restored.scalars(select(AlertEvent)).all()), 1)
            restored_engine.dispose()
        finally:
            if os.path.exists(backup_path):
                os.unlink(backup_path)
            source_engine.dispose()

    def test_non_sqlite_database_backup_is_rejected(self):
        main.settings.database_url = "postgresql://example.invalid/database"
        with self.assertRaises(HTTPException) as error:
            main.download_database_backup()
        self.assertEqual(error.exception.status_code, 501)


if __name__ == "__main__":
    unittest.main()
