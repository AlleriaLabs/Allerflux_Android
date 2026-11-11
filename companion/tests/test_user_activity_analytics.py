import unittest
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from companion.app.database import Base
from companion.app.models import SessionSnapshot
from companion.app.main import analytics_last_24_hours


class UserActivityAnalyticsTests(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine(
            "sqlite://",
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
        )
        Base.metadata.create_all(self.engine)
        self.db = Session(self.engine)

    def tearDown(self):
        self.db.close()
        self.engine.dispose()

    def test_hourly_online_peak_and_unique_users_include_idle_sessions(self):
        now = datetime.now(timezone.utc)
        self.db.add_all([
            SessionSnapshot(jellyfin_session_id="s1", user_id="u1", username="One",
                item_id=None, item_name=None, media_type=None, play_state="idle",
                position_ticks=0, captured_at=now, raw_json="{}"),
            SessionSnapshot(jellyfin_session_id="s2", user_id="u2", username="Two",
                item_id="m1", item_name="Movie", media_type="Movie", play_state="playing",
                position_ticks=100, captured_at=now, raw_json="{}"),
            SessionSnapshot(jellyfin_session_id="s3", user_id="u3", username="Three",
                item_id=None, item_name=None, media_type=None, play_state="idle",
                position_ticks=0, captured_at=now + timedelta(seconds=1), raw_json="{}"),
        ])
        self.db.commit()

        result = analytics_last_24_hours(self.db)
        self.assertEqual(result["timezone"], "Asia/Manila")
        self.assertEqual(len(result["points"]), 24)
        point = result["points"][-1]
        self.assertEqual(point["online_users"], 3)
        self.assertEqual(point["unique_users"], 3)

    def test_empty_history_returns_24_zero_filled_hours(self):
        result = analytics_last_24_hours(self.db)
        self.assertEqual(len(result["points"]), 24)
        self.assertTrue(all(p["online_users"] == 0 and p["unique_users"] == 0
                            for p in result["points"]))


if __name__ == "__main__":
    unittest.main()
