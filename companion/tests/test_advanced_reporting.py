import unittest
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from companion.app.database import Base
from companion.app.models import SessionSnapshot, PlaybackEvent
from companion.app.main import reports_summary, reports_compare


class AdvancedReportingTests(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine(
            "sqlite://",
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
        )
        Base.metadata.create_all(self.engine)
        self.db = Session(self.engine)
        now = datetime.now(timezone.utc)
        self.now = now
        self.db.add_all([
            SessionSnapshot(jellyfin_session_id="s-current", user_id="u1", username="Viewer 1",
                item_id="m1", item_name="Current Movie", media_type="Movie",
                play_state="playing", position_ticks=10, captured_at=now - timedelta(days=2), raw_json="{}"),
            SessionSnapshot(jellyfin_session_id="s-previous", user_id="u2", username="Viewer 2",
                item_id="m2", item_name="Previous Movie", media_type="Movie",
                play_state="paused", position_ticks=20, captured_at=now - timedelta(days=9), raw_json="{}"),
            PlaybackEvent(event_key="e-current", event_type="PlaybackStart", user_id="u1",
                username="Viewer 1", item_id="m1", item_name="Current Movie", media_type="Movie",
                occurred_at=now - timedelta(days=1), raw_json="{}"),
            PlaybackEvent(event_key="e-previous", event_type="PlaybackStart", user_id="u2",
                username="Viewer 2", item_id="m2", item_name="Previous Movie", media_type="Movie",
                occurred_at=now - timedelta(days=8), raw_json="{}"),
        ])
        self.db.commit()

    def tearDown(self):
        self.db.close()
        self.engine.dispose()

    def test_weekly_rollup_preserves_snapshot_totals(self):
        result = reports_summary(days=14, group_by="week", db=self.db)
        self.assertEqual(result["group_by"], "week")
        self.assertEqual(sum(row["snapshots"] for row in result["daily"]), 2)
        self.assertLessEqual(len(result["daily"]), 3)

    def test_monthly_rollup_returns_month_buckets(self):
        result = reports_summary(days=365, group_by="month", db=self.db)
        self.assertEqual(result["group_by"], "month")
        self.assertEqual(sum(row["snapshots"] for row in result["daily"]), 2)
        self.assertTrue(all(len(row["date"]) == 10 for row in result["daily"]))

    def test_custom_date_range_uses_requested_timezone(self):
        local_today = datetime.now(ZoneInfo("Asia/Manila")).date()
        local_start = local_today - timedelta(days=3)
        result = reports_summary(
            days=30, group_by="day", from_date=local_start, to_date=local_today,
            timezone_name="Asia/Manila", db=self.db,
        )
        self.assertEqual(result["from_date"], local_start.isoformat())
        self.assertEqual(result["to_date"], local_today.isoformat())
        self.assertEqual(result["timezone"], "Asia/Manila")
        self.assertEqual(result["snapshot_count"], 1)
        self.assertEqual(sum(row["snapshots"] for row in result["daily"]), 1)

    def test_custom_date_range_validation(self):
        from fastapi import HTTPException

        with self.assertRaises(HTTPException) as incomplete:
            reports_summary(days=30, from_date=date(2026, 11, 1), to_date=None, db=self.db)
        self.assertEqual(incomplete.exception.status_code, 422)
        with self.assertRaises(HTTPException) as reversed_range:
            reports_summary(days=30, from_date=date(2026, 11, 12),
                to_date=date(2026, 11, 11), db=self.db)
        self.assertEqual(reversed_range.exception.status_code, 422)
        with self.assertRaises(HTTPException) as bad_timezone:
            reports_summary(days=30, timezone_name="Not/A_Real_Zone", db=self.db)
        self.assertEqual(bad_timezone.exception.status_code, 422)

    def test_previous_period_uses_local_calendar_boundaries_across_dst(self):
        local_tz = ZoneInfo("America/New_York")
        previous_midnight_utc = datetime.combine(
            date(2026, 2, 23), datetime.min.time(), tzinfo=local_tz
        ).astimezone(timezone.utc)
        self.db.add(SessionSnapshot(
            jellyfin_session_id="dst-previous", user_id="dst-user", username="DST Viewer",
            item_id="dst-item", item_name="DST Previous Period", media_type="Movie",
            play_state="playing", position_ticks=1, captured_at=previous_midnight_utc, raw_json="{}"
        ))
        self.db.commit()
        result = reports_compare(
            days=7, from_date=date(2026, 3, 2), to_date=date(2026, 3, 8),
            timezone_name="America/New_York", db=self.db
        )
        self.assertEqual(result["metrics"]["snapshot_count"]["previous"], 1)
        self.assertEqual(result["metrics"]["snapshot_count"]["current"], 0)

    def test_previous_period_comparison_separates_snapshots_and_events(self):
        result = reports_compare(days=7, db=self.db)
        self.assertEqual(result["metrics"]["snapshot_count"]["current"], 1)
        self.assertEqual(result["metrics"]["snapshot_count"]["previous"], 1)
        self.assertEqual(result["metrics"]["playback_starts"]["current"], 1)
        self.assertEqual(result["metrics"]["playback_starts"]["previous"], 1)


if __name__ == "__main__":
    unittest.main()
