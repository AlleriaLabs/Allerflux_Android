import unittest

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

import companion.app.main as main
from companion.app.database import Base
from companion.app.models import AlertEvent, AlertState


class AlertStateTests(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine(
            "sqlite://",
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
        )
        Base.metadata.create_all(self.engine)
        self.original_session_local = main.SessionLocal
        main.SessionLocal = sessionmaker(bind=self.engine, autoflush=False, autocommit=False)
        self.original_cooldown = main.settings.alert_cooldown_minutes
        main.settings.alert_cooldown_minutes = 60

    def tearDown(self):
        main.SessionLocal = self.original_session_local
        main.settings.alert_cooldown_minutes = self.original_cooldown
        self.engine.dispose()

    def test_alert_triggers_once_and_records_recovery(self):
        first = main.change_alert_state(
            "idle:session-1", "idle_session", "Idle session", "Test idle alert", True
        )
        duplicate = main.change_alert_state(
            "idle:session-1", "idle_session", "Idle session", "Test idle alert", True
        )
        recovered = main.change_alert_state(
            "idle:session-1", "idle_session", "Idle session cleared", "Recovered", False
        )
        self.assertEqual(first["status"], "active")
        self.assertIsNone(duplicate)
        self.assertEqual(recovered["status"], "resolved")
        db = main.SessionLocal()
        try:
            events = db.scalars(select(AlertEvent).order_by(AlertEvent.id)).all()
            state = db.get(AlertState, "idle:session-1")
            self.assertEqual([event.status for event in events], ["active", "resolved"])
            self.assertFalse(state.is_active)
        finally:
            db.close()

    def test_inactive_alert_does_not_create_history(self):
        result = main.change_alert_state(
            "idle:session-2", "idle_session", "Idle session cleared", "No alert", False
        )
        self.assertIsNone(result)
        db = main.SessionLocal()
        try:
            self.assertEqual(db.query(AlertEvent).count(), 0)
        finally:
            db.close()


if __name__ == "__main__":
    unittest.main()
