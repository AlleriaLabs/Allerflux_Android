import unittest
from datetime import datetime, timedelta, timezone

from companion.app.main import item_info


class ItemInfoTests(unittest.TestCase):
    def test_idle_session_without_media_is_retained_and_has_idle_duration(self):
        last = datetime.now(timezone.utc) - timedelta(minutes=5)
        row = item_info({
            "Id": "session-idle",
            "UserId": "user-1",
            "UserName": "Viewer",
            "DeviceName": "Living room TV",
            "Client": "Jellyfin Android TV",
            "ApplicationVersion": "1.2.3",
            "LastActivityDate": last.isoformat(),
        })
        self.assertEqual(row["play_state"], "idle")
        self.assertIsNone(row["item_id"])
        self.assertIsNone(row["item_name"])
        self.assertGreaterEqual(row["idle_seconds"], 299)
        self.assertLessEqual(row["idle_seconds"], 301)
        self.assertEqual(row["device_name"], "Living room TV")
        self.assertEqual(row["client_version"], "1.2.3")

    def test_playing_session_exposes_title_and_position(self):
        row = item_info({
            "Id": "session-playing",
            "UserName": "Viewer",
            "NowPlayingItem": {"Id": "movie-1", "Name": "Example Film", "Type": "Movie"},
            "PlayState": {"PositionTicks": 900000000},
        })
        self.assertEqual(row["play_state"], "playing")
        self.assertEqual(row["item_id"], "movie-1")
        self.assertEqual(row["item_name"], "Example Film")
        self.assertEqual(row["position_ticks"], 900000000)

    def test_paused_session_is_not_reported_as_playing(self):
        row = item_info({
            "Id": "session-paused",
            "NowPlayingItem": {"Id": "episode-1", "Name": "Example Episode", "Type": "Episode"},
            "PlayState": {"IsPaused": True, "PositionTicks": 100},
        })
        self.assertEqual(row["play_state"], "paused")

    def test_missing_and_invalid_activity_timestamps_are_safe(self):
        for payload in ({"Id": "no-time"}, {"Id": "bad-time", "LastActivityDate": "not-a-date"}):
            with self.subTest(payload=payload):
                row = item_info(payload)
                self.assertIsNone(row["last_activity"])
                self.assertIsNone(row["idle_seconds"])

    def test_raw_session_payload_is_preserved_for_diagnostics(self):
        row = item_info({"Id": "session-raw", "CustomField": "value"})
        self.assertIn('"CustomField":"value"', row["raw_json"])


if __name__ == "__main__":
    unittest.main()
